# ADR 0006: Non-blocking retry, DLQ and idempotent delivery

Status: accepted

## Problem

The delivery worker retried inside the Kafka listener thread with `Thread.sleep` (500 ms, 1 s, 2 s, and one more sleep after the final failure). While a record slept, its partition was stalled and a slow provider could park every consumer thread. A crash or rebalance during retries re-delivered the record and could send the message twice. Failures were sent to the DLQ with no reason, and the result event was published fire-and-forget, so a broker hiccup lost it.

## Options

1. Keep in-process retry, shorten sleeps. Simple, but still blocks the lane and cannot express minute-scale delays.
2. Spring Kafka `@RetryableTopic`. Built in, but it names the DLT `<topic>-dlt`, auto-creates topics, and does not give us control over tier choice from a `Retry-After` value.
3. `DefaultErrorHandler` with zero in-place backoff plus a custom `DeadLetterPublishingRecoverer` destination resolver that picks a delay-tier topic (`notification.requested.retry.5s|30s|2m`) or the DLQ.
4. External scheduler (DB-polled retry table). More moving parts and one more store.

## Decision

Option 3.

- The listener never sleeps or loops. A failure throws; the error handler (`FixedBackOff(0,0)`) calls `DeliveryRecoverer`, which republishes the record to tier `n` (header `x-attempt`) or to the DLQ.
- `RetryTierConsumer` reads the tier topics and holds a record until `record.timestamp + tier delay` using `Acknowledgment.nack(Duration)`. Only the retry listener's own threads wait; lane threads are untouched. Records in one tier partition are time-ordered, so waiting for the head is exact.
- A provider `Retry-After` (429) or circuit-open wait selects the smallest tier that covers it and never moves backwards.
- Permanent errors (HTTP 4xx, invalid address) and undeserializable records skip the tiers and go to the DLQ. Deserialization uses `ErrorHandlingDeserializer`; poison bytes are dead-lettered intact through a byte[] template.
- DLQ records carry `x-attempt`, `x-failure-reason`, `x-failure-class`, `x-failed-at`, `x-original-topic`, plus Spring's `kafka_dlt-exception-*` headers.
- Before dead-lettering, the recoverer publishes a `FAILED` result (awaited) so quota and analytics see every failure. If that publish fails, the recoverer throws and the error handler re-seeks, so nothing is lost.
- Idempotency: table `notification_delivery` in `delivery_db` (Flyway `V1`). The first step of processing is a unique insert on `notification_id`. States: `IN_PROGRESS < SENT < DELIVERED | BOUNCED | FAILED`; updates are guarded by `status_rank`, so state never regresses. A redelivered record whose row is past `IN_PROGRESS` is not sent again; if only the result event is missing (`result_published=false`) it is republished. The notification id is also passed to providers as `Idempotency-Key`.
- Result publishing is awaited with an idempotent, `acks=all` producer; a failure throws and retries.
- Replay: `POST /api/v1/delivery/admin/dlq/replay?limit=N` reads the DLQ with a dedicated consumer group, resets the `FAILED` row, and republishes to `x-original-topic`. Loop guard: header `x-replay-count` is incremented per replay and records at `app.delivery.replay.max-replays` (default 1) stay in the DLQ.

## Trade-offs

- A real crash window remains between "provider accepted" and "row marked SENT". The provider idempotency key closes it for HTTP providers that honour it; SMTP has no key, so a crash in that window can send one duplicate email. This is the unavoidable at-least-once boundary.
- `nack` sleeps the retry thread, so a 2 min tier needs `max.poll.interval.ms` above 2 min (set to 5 min) and tier concurrency bounds throughput of retries. Acceptable because retries are the exceptional path.
- Retry topics are shared by all lanes; the `priority` header is preserved, so a retried OTP is processed with the high lane label, but it queues behind other retries in the same tier.
- The status enum in the shared Avro schema has no DELIVERED/BOUNCED. Delivered is published as `ACKNOWLEDGED` and bounced as `FAILED` until the schema owners add symbols.

## Topics

`notification.requested.retry.5s`, `.30s`, `.2m` (12 partitions each), created by `kafka-init`. Auto-creation stays disabled.
