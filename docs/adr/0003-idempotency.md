# ADR 0003: Idempotent `POST /send`

Status: accepted. Scope: notification-service.

## Problem

Clients retry. A timeout, a gateway retry or a double click sends the same request twice. Before this
change every call created a new notification and a new Kafka event, so a retry meant a duplicate
message to the end user (and a duplicate quota charge). The old code had no idempotency key at all.

We need: the same logical request produces exactly one notification, however many times and however
concurrently it arrives, without a slow hot path.

## Options

| Option | Pro | Con |
|---|---|---|
| A. Redis only (`SET NX`) | Fast, simple | Redis is a cache: after a flush or failover the guarantee is gone |
| B. Postgres unique constraint only | Strongest guarantee | One failed insert per duplicate, no "in flight" signal, DB on every duplicate |
| C. Redis fast path + Postgres unique as final arbiter | Fast common case, correct after Redis loss | Two mechanisms to keep consistent |
| D. Content hash only | No client change | Cannot tell a legitimate resend from a retry |

## Decision

Option C, with a content-hash fallback that tenants opt into.

- Header `Idempotency-Key` (1 to 255 printable chars), scoped per tenant, kept 24h. The gateway passes
  the header through unchanged; notification-service reads it.
- Request fingerprint = SHA-256 of the canonical payload (channel, recipient, subject, content,
  templateId, priority, sorted variables, length-prefixed so field boundaries are unambiguous).
- Redis key `idem:{tenant}:{key}`:
  - `P|fingerprint|owner` while in flight (TTL 60s, so a crashed request frees the key),
  - `D|fingerprint|notificationId` once finished (TTL 24h).
  - Claim is one `SET NX PX`, so exactly one concurrent caller proceeds.
- Outcomes:
  - same key, same fingerprint, finished: replay the original response (HTTP 202, header
    `Idempotent-Replayed: true`, same notification id, no new quota use);
  - same key, different fingerprint: **422**;
  - same key, same fingerprint, still in flight: **409** (client retries later);
  - first caller: proceed.
- Postgres: unique index on `(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL` on
  `notifications`. If two requests both pass Redis (Redis was down or lost), the insert of the second
  fails with a duplicate-key error; it releases its quota hold and answers as a replay (or 422 if the
  stored fingerprint differs). A cleanup job nulls the key after 24h so both sides expire together.
- Content-hash fallback is per-tenant config (`tenant_idempotency_config`: `HEADER_ONLY` default, or
  `HEADER_FIRST` = use the header when present, otherwise dedupe by content hash within
  `hash_window_seconds`, default 60s). Hash keys live in Redis only and are never persisted: a DB
  unique key on a content hash would block a legitimate identical message forever.

## Trade-offs

- Two hops on the first request (Redis claim, then DB insert) cost about one extra Redis round trip.
- In-flight TTL of 60s means a request that hangs longer than that may be run twice. The DB unique key
  still prevents two notifications for header keys. Hash-only keys have no such backstop.
- `HEADER_FIRST` can swallow a legitimate identical resend inside the window. That is why it is opt-in.
- A replay returns the response of the original accept (status PENDING), not the current status. Use
  the status endpoint for the current state.

## Failure modes

| Failure | Behavior |
|---|---|
| Redis down, `write-mode=outbox` | Redis fast path skipped (logged). DB unique key arbitrates. Concurrent duplicates cost an extra insert and a quota release, but never produce two notifications. |
| Redis down, `write-mode=kafka-first` | **Fail closed, 503.** There is no DB arbiter on the request path (the row is created later by a consumer), so accepting could publish duplicates. |
| Redis flushed or failover | Same as down for the keys lost. Header keys stay safe in outbox mode through the DB constraint. |
| Crash after claim, before write | In-flight key expires after 60s. Client retry then proceeds. |
| Write fails after claim | Claim is aborted (compare-and-delete by owner) and quota released. Retry with the same key works at once. |
| `complete` fails after a successful write | Logged. The key stays in flight until its 60s TTL; the DB unique key (outbox) still blocks a duplicate. |
| Two instances, same key | Only one wins `SET NX`; the other gets 409 or a replay. |

Tests: `SendNotificationUseCaseTest` (64 concurrent identical requests produce exactly one
notification, with and without Redis), `RedisIdempotencyStoreRedisTest` (real Redis), and
`PostgresWritePathTest` (32 concurrent inserts, one row).
