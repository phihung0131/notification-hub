# ADR 0009: Analytics ingestion, ordering and status state machine

Status: accepted

## Context

analytics-service consumes `notification.requested` and `notification.result` and keeps one row per
message. Problems found in the previous design:

- Events were buffered in two in-memory lists, copied then cleared non-atomically. Kafka offsets were
  already committed, so a crash or a concurrent `add` during flush lost events.
- A late `requested` event (status PENDING) overwrote a row already at SENT. The two topics have no
  cross-topic ordering, so this happens in normal operation.
- `ddl-auto: update`, single-column indexes, unpaginated tenant listing.

## Decision

1. **No in-memory buffering.** Kafka batch listeners (`listener.type=batch`, `ack-mode=manual`,
   `enable-auto-commit=false`, `max-poll-records` = batch size). A batch is folded per message id and
   written in one DB transaction; the offset is acknowledged only after commit. Any failure skips the ack
   and a `DefaultErrorHandler` with unbounded capped exponential backoff (1s to 30s) redelivers the batch.
   Delivery is at-least-once, so the write must be idempotent. Events lacking `id` or `tenantId` are dropped
   (counted in a warn log) instead of failing the batch, which prevents a poison-pill retry loop.
2. **Forward-only status.** Ranks: PENDING 0, SENT 1, FAILED 2, READ 3, ACKNOWLEDGED 4. A status moves only to
   a strictly higher rank, and FAILED is terminal (never replaced, not even by READ). The enum in
   commons-shared has no DELIVERED/BOUNCED yet; when added they slot in by rank (DELIVERED above SENT,
   BOUNCED terminal like FAILED). A late `requested` can therefore never regress SENT; a result arriving
   before `requested` creates the row, and the later `requested` only back-fills null fields and an earlier
   `created_at`.
3. **Database arbitrates.** `INSERT ... ON CONFLICT (message_id) DO UPDATE ... WHERE <guard>` via JDBC
   batch. The guard compares the stored `status_rank` with `EXCLUDED.status_rank` (and `status <> 'FAILED'`),
   so concurrent consumers, replays and reordering are safe without locks or read-modify-write. Rows with no
   effective change are skipped (no dead tuples). The same rules live in `MessageStatusProgression` /
   `MessageRecord.mergeWith`, used to fold events inside a batch and by the unit tests.
4. **Versioning choice.** The Avro event carries only `createdAt`, no per-event timestamp or version, so a
   timestamp guard is impossible without a schema change. The status rank is a total order over the
   lifecycle and serves as the version. Revisit if the schema gains an event time.
5. **Flyway** (`V1__create_messages.sql`) replaces `ddl-auto: update`; Hibernate runs with `validate`
   (the service now uses JDBC, no JPA entities). Indexes: `(tenant_id, created_at DESC, message_id DESC)`
   for keyset listing and `(tenant_id, status)` for stats and filters.
6. **Query API.** `GET /messages` is keyset-paginated (opaque cursor over `(created_at, message_id)`, limit
   default 50, max 200) with `status`, `channel`, `from` (inclusive), `to` (exclusive) filters.
   `GET /messages/stats` returns counts by status for the tenant (zero-filled). Keyset avoids OFFSET cost
   and stays stable while new rows arrive.

## Consequences

- Crash-safe and replay-safe; duplicates cost one skipped upsert row.
- Because FAILED is terminal, a provider that reports FAILED then later SENT is not reflected; this matches
  the delivery state machine rule of final terminal states.
- Same-batch folding means one statement per message even when 3 events for it arrive together.
- Throughput is bounded by batch size and one transaction per poll; tune with `ANALYTICS_BATCH_SIZE` and
  `ANALYTICS_CONSUMER_CONCURRENCY` (up to partition count).
- Tests are unit level (in-memory port with the same merge semantics, plus a SQL guard assertion). The SQL
  itself is exercised by the Testcontainers harness (Postgres) once it exists.
