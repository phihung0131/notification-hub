# ADR 0008: Priority lanes, recipient throttle, opt-out

Status: accepted

## Problem

A bulk campaign on `notification.requested` delays one-time passwords queued behind it. One FIFO topic and one thread pool give no priority. Separately, a bug or abuse can spam one recipient, and users who opted out must not be contacted.

## Options

1. Priority inside one topic. Kafka cannot reorder; head-of-line blocking remains.
2. Separate topics per priority, one listener container each, with different thread counts (weighted consumption).
3. Strict priority: consume low only when high is empty. Starves low under sustained high load.

## Decision

Option 2.

- Topics: `notification.requested.high`, `notification.requested` (normal), `notification.requested.low`, 12 partitions each.
- Lane listeners: high 6 threads / 50 polled records, normal 4 / 50, low 2 / 20 (`app.delivery.lanes.*`). Thread counts are capped by partitions. Weighted, not strict, so low always makes progress; high has the most threads and its own bulkhead permits per channel, so a flood on low cannot take them.
- The event schema has no priority field, so the lane comes from an optional record header `priority` (`high|urgent|otp`, `low|bulk`, else normal). Producers should publish straight to the lane topic. Records that arrive on the default topic with a `priority` header are forwarded once to the right lane (header `x-routed` stops loops). Without a header everything is normal, which is the current behaviour of notification-service.
- Per-recipient guard (`RecipientGuard`, Redis `redis-core`): a sliding-window limiter (sorted set + Lua, atomic) of `max-per-window` sends per tenant, channel and recipient; and a content dedup key (`SET NX` with TTL) for identical subject and content. The notification's own id is whitelisted so its retries are not counted as duplicates. Over limit raises a retryable error with the time until the oldest entry expires; duplicates are suppressed with a `FAILED` result. Redis errors fail open.
- Opt-out list: Postgres table `opt_out (tenant_id, recipient)` managed by `PUT/DELETE /admin/opt-out`. Opted-out sends are suppressed and reported as `FAILED` so quota is released.

## Trade-offs

- Producers must cooperate to use lanes; until notification-service sets the topic or header, all traffic is normal.
- Lane share is static configuration. Thread totals (12 per lane topic here) must be tuned to the host; the RAM-constrained default is smaller if instances scale out.
- Retry tiers are shared across lanes (ADR 0006).
- Failing open on Redis means throttling and dedup are best effort during a Redis outage; the processed-id table still prevents double sends of the same notification.
- Suppression publishes `FAILED` because the status enum has no `SUPPRESSED`; the DLQ is not used for it.
