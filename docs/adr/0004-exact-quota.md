# ADR 0004: Exact quota with reserve, commit and release

Status: accepted. Scope: tenant-service (owner), notification-service (client), commons-shared (proto).

## Problem

The old design could not enforce a limit:

- notification-service decremented a local Redis cache (`notification:quota:{tenant}`) while
  tenant-service kept its own cache (`quota:usage|limit`). The two drifted apart.
- Nothing was held at accept time. N parallel requests all saw "quota left" and all passed.
- The tenant-side update `quota_used = quota_used + n` had no limit guard.
- Any error in the check returned "allowed" (fail open).
- The result consumer acknowledged before it wrote, so a crash lost billing records.

Requirement: committed usage never exceeds the limit, under concurrency, and survives restarts.

## Options

| Option | Pro | Con |
|---|---|---|
| A. DB counter with `UPDATE ... WHERE used < limit` on every request | Exact, simple | DB write on the hot path; row lock per tenant limits throughput |
| B. Redis `DECR` counter | Fast | Lost on Redis failure, no way to give back a failed send |
| C. Redis Lua reserve/commit/release + Postgres as durable truth | Fast and exact, failed sends give quota back | More moving parts, needs reconciliation |

## Decision

Option C. tenant-service owns limits and exposes gRPC `ReserveQuota`, `CommitQuota`, `ReleaseQuota`
(`tenant.proto`). notification-service no longer keeps any quota state.

- **Reserve** (hot path, one atomic Lua script per call): purge expired holds, then if
  `used + held >= limit` answer EXCEEDED, else add `reservationId` to a sorted set with expiry
  `now + ttl`. Idempotent per `reservationId` (the notification id), so retries do not take a second
  unit. Time comes from Redis `TIME`, not from app hosts. Keys are hash-tagged per tenant.
  `limit = -1` means unlimited.
- **Commit** (on result `SENT`): in one DB transaction record the message id in `processed_events`
  (`INSERT ... ON CONFLICT DO NOTHING`) and add the number of newly recorded ids to
  `tenants.quota_used`, capped at the limit under a row lock (`SELECT ... FOR UPDATE`). Then settle
  Redis: drop the holds and move `used` forward to the durable value (`max`, never backwards).
- **Release** (on result `FAILED`, or the request aborted): remove the hold. A hold nobody settles
  expires by TTL (default 15 min), so a crash between reserve and commit reclaims the unit by itself.
- **Durable truth**: `tenants.quota_used` in Postgres. Redis holds only the hot copy plus the
  in-flight holds.
- **Reconciliation**: at startup and every 60s, tenant-service syncs `limit` (overwrite) and `used`
  (max) from Postgres into Redis. After a Redis flush the ledger is rebuilt lazily on the first
  reserve (`NOT_INITIALIZED`, then init from DB) and by the job. Because `used` only moves forward,
  a sync racing a commit cannot double count.
- **Result consumer** (replaces the buggy one): batch listener, manual ack, ack only after
  `ResultEventProcessor.process` returned (DB written). No in-memory buffer. Dedup inside the batch
  and across redeliveries via the `processed_events` unique insert. The transactional boundary is a
  separate bean (`JdbcQuotaStore`), so there is no self-invocation of `@Transactional`. Errors are
  retried forever with a 1s pause instead of being skipped. `processed_events` retention is 30 days
  (Kafka retention of the result topic is 7 days), so a late redelivery cannot be counted twice.
- **Removed**: the notification-side cache, `QuotaCheckService`, the unguarded
  `incrementQuotaUsed`, and the tenant-side `quota:usage|limit` keys.

## Fail policy

If the quota service or its ledger cannot answer (gRPC deadline 1s, `UNAVAILABLE`, Redis down),
`POST /send` answers **503 with `Retry-After`** (fail closed). Rationale: this is a billing limit.
Accepting while blind would let tenants exceed it, and we cannot take it back afterwards. A client
that retries with an `Idempotency-Key` loses nothing. Availability of `/send` therefore depends on
tenant-service and redis-core. The legacy `CheckQuota` RPC stays a non-binding read.

## Trade-offs

- One gRPC hop and one Redis script per accepted request (about 1 to 2 ms on a LAN).
- Holds are not in Postgres. If Redis loses its data, the holds of in-flight notifications vanish.
  For a short window the tenant can be granted up to "number of in-flight holds" extra units. The
  hard guarantee stays in Postgres: **commit is capped at the limit**, so committed usage never
  exceeds it. A commit that would exceed it is recorded as `LIMIT_REACHED`, the message is still
  delivered, and a warning is logged (we do not un-send a message).
- A `SENT` result that arrives after its hold expired still commits (delivery happened). If other
  requests used the freed unit meanwhile, this can end as `LIMIT_REACHED`. Choose a TTL longer than
  the worst delivery time including retries.
- The Redis settle step after the DB commit can fail. The batch is then redelivered and the DB step
  answers DUPLICATE, so the settle is retried: no double count, no lost settle.

## Failure modes

| Failure | Behavior |
|---|---|
| Redis down | Reserve fails, `/send` returns 503. Commits wait (batch retried). No quota is lost. |
| Redis flushed | Ledger rebuilt from Postgres. Possible small over-grant window, committed usage still capped. |
| Postgres down | Result consumer retries; reserve still works from Redis until the ledger needs a rebuild. |
| tenant-service down | `/send` returns 503. Result events wait in Kafka. |
| Crash between reserve and result | Hold expires after TTL. |
| Duplicate / redelivered result event | Counted once (unique insert). |
| Tenant limit lowered below usage | Reserve refuses; `used` is never reduced. |

Tests: `QuotaReservationServiceTest` (400 parallel reserves on limit 50 grant exactly 50;
reserve/commit/release under load; TTL expiry; rebuild after Redis loss), `ResultEventProcessorTest`
(duplicates counted once; crash between DB and Redis heals), `RedisQuotaLedgerRedisTest` (the Lua
scripts on a real Redis, 300 parallel reserves) and `PostgresQuotaStoreTest` (real Postgres: capping,
idempotency, concurrent commits of one message).
