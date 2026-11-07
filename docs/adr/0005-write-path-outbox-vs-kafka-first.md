# ADR 0005: Write path, transactional outbox vs Kafka-first

Status: accepted. Switch: `notification.write-mode=outbox|kafka-first` (env `NOTIFICATION_WRITE_MODE`,
default `outbox`). Scope: notification-service.

## Problem

The old `send` saved the row, then called `kafkaTemplate.send(...)` fire-and-forget inside the same
transaction:

- DB commit and Kafka publish are not atomic: a crash or a failed async send left a PENDING row with
  no event (lost notification), or an event for a rolled-back row.
- The send result was never checked, and `Notification.status` stayed PENDING forever.

We want to answer 202 only when the notification cannot be lost, and we want to measure the cost of
the two classic ways to get there.

## Options and decision

Both are implemented behind one `WritePath` port. The idempotency and quota steps are identical; only
"make it durable" differs.

### `outbox` (default)

1. Redis idempotency claim, quota reserve (see ADR 0003, 0004).
2. **One DB transaction** inserts `notifications` (PENDING) and an `outbox_events` row.
3. Respond 202.
4. `OutboxRelay` polls: `SELECT ... WHERE sent_at IS NULL ORDER BY created_at, id LIMIT n FOR UPDATE
   SKIP LOCKED`, publishes the batch (producer `acks=all`, `enable.idempotence=true`), waits for all
   acks, sets `sent_at`, commits. Any failure rolls back and the rows are retried. Several instances
   never take the same rows. Messages are keyed by `tenantId`. Published rows are purged after 1h.

### `kafka-first`

1. Redis idempotency claim, quota reserve.
2. Publish to Kafka synchronously (`acks=all`, idempotent producer, keyed by `tenantId`), wait for the
   ack. Respond 202.
3. A consumer (`NotificationWriterListener`, own consumer group) creates the DB row with
   `INSERT ... ON CONFLICT DO NOTHING`, manual ack after the write.

### Status updates (both modes)

A consumer of `notification.result` moves `Notification.status` forward with a guarded
`UPDATE ... WHERE status IN (allowed sources)`. State machine: `PENDING -> SENT | FAILED`,
`FAILED -> SENT` (DLQ replay), `SENT` final; it never regresses. Duplicates and late events are
ignored. If the row does not exist yet (kafka-first lag) the batch is retried for about 10s.

## Comparison

| | outbox | kafka-first |
|---|---|---|
| Accept latency | DB commit (fsync) | Kafka ack (replication) |
| Durable once 202 sent | in Postgres | in Kafka (`acks=all`) |
| Event to consumers | after relay poll (default 100ms), at-least-once | immediately |
| Idempotency arbiter | Redis + Postgres unique key | Redis only on the request path |
| Status visible right after 202 | yes (row exists) | no, lags until the writer consumer runs |
| DB load on accept | 2 inserts per request | 0 on accept, inserts moved to a consumer |
| Redis down | degrades, DB arbitrates | fail closed 503 |
| Kafka down | accepts, outbox grows, relay catches up | fail closed 503 |
| Postgres down | 503 | accepts, rows are written when it recovers |
| Moving parts | relay, outbox table | writer consumer |

Default is `outbox`: it keeps the strongest guarantees with the fewest dependencies on the request
path (Kafka can be down and we still accept). `kafka-first` is available for lower accept latency and
DB-independent ingest. Benchmarks of both are produced by the load-test phase.

## Trade-offs and failure modes

- Outbox delivery is at-least-once: a crash after Kafka acked but before `sent_at` committed
  republishes the batch. Consumers must dedupe by notification id (tenant-service already does,
  delivery and analytics are on their own).
- Per-key ordering: a single relay preserves `created_at` order per tenant. With several relay
  instances `SKIP LOCKED` can publish a later batch before an earlier locked one, so order is
  best-effort. Keying by `tenantId` also puts a hot tenant on one partition; this is deliberate
  (ordering over spread).
- kafka-first: if the request fails after the Kafka ack (for example a crash before the HTTP
  response), the client retries with the same `Idempotency-Key`; Redis (still holding the in-flight or
  done key) prevents a second publish. If Redis is lost at that moment a duplicate event is possible
  and the writer consumer's `ON CONFLICT` plus downstream dedupe contain it.
- kafka-first: the `notifications` row can appear seconds after the 202, so status queries must
  tolerate "not found yet". A result event can arrive before the row; it is retried.
- kafka-first writer consumer retries forever on DB errors (never skip a row), which can build lag
  during a long Postgres outage; nothing is lost.
- Producer config for both: `acks=all`, idempotence on, `max.in.flight=5`, lz4, `linger.ms=5`.
- Event schema change (Avro, backward compatible, all new fields optional with default null):
  `priority`, `idempotencyKey`, `requestFingerprint`, `version`, `eventTimestamp`.

Tests: `PostgresWritePathTest` on a real Postgres (notification + outbox in one transaction, a failed
transaction writes neither, relay publishes each row once, rollback on broker failure, two relays
never publish the same row), `SendNotificationUseCaseTest`, `StatusUpdateServiceTest` (no regression,
duplicates harmless).
