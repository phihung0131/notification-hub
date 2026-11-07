# Notification Hub — Engineering Report (2026-11-07)

Scope: take the skeleton notification hub to a fully dockerized, correctness-first platform with mock
providers, then test it under load. This report states what was built, what was **measured**, and what was
**not** done. Nothing here is extrapolated beyond the runs listed.

## 1. What changed (all merged to `master`, PRs #1–#10 plus this one)

| Area | Change | Evidence |
|---|---|---|
| Docker | One `compose.yaml`, multi-stage `Dockerfile`, healthchecks with ordered startup, explicit Kafka topic init, 1 Postgres (db+role per service), 2 Redis, Mailpit, optional Prometheus/Grafana/Jaeger, `Makefile` entrypoints | `make up` -> all containers healthy; `make smoke` -> 202 |
| Idempotency | `Idempotency-Key` per tenant (Redis `SET NX` + Postgres unique index): replay -> 202, different payload -> 422, concurrent in-flight -> 409 | `scripts/consistency.sh`: 50 concurrent same-key sends -> exactly 1 row |
| Quota | Redis Lua reserve/commit/release owned by tenant-service, Postgres durable truth, reconciliation job, fail-closed (503) | `scripts/consistency.sh`: limit 25, 100 concurrent sends -> exactly 25 accepted, `quota_used = 25` |
| Write path | `NOTIFICATION_WRITE_MODE=outbox|kafka-first` | Both modes ran under load (see §3) |
| Result consumer | Offset acked after durable DB write, dedup by unique insert | Unit tests; duplicate events counted once |
| Delivery | Retry-tier topics (5s/30s/2m), DLQ with reason headers + replay endpoint, idempotent consumer, per-channel bulkhead + circuit breaker, Mailpit/HTTP adapters, signed provider webhooks, priority lanes | 40 unit tests; end-to-end flow verified on the stack |
| Analytics | Atomic batch upsert, one-way status machine, keyset pagination, filters, stats | Unit tests; reconciliation PASS |
| Gateway | Non-blocking auth call with deadline, key cache, 503 (not 401) on dependency failure, per-tenant rate limit | Unit tests; 503 observed on cold gRPC channel |
| Tooling | k6 scenarios, reconciliation verifier, chaos scripts, Grafana dashboards | `make bench`, `make verify` |

ADRs: `docs/adr/0003`–`0010` (idempotency, exact quota, write path, delivery retry/DLQ, provider resilience,
priority lanes, analytics ordering, gateway auth/rate limit).

## 2. Bugs found only by running the integrated stack

Unit tests passed on every branch; these appeared only when services ran together.

1. **Column width mismatch (blocked partitions).** `delivery_db.notification_delivery.channel` and
   `analytics_db.messages.channel` were `VARCHAR(32)`; notification-service accepts channel codes up to 255.
   A long code made the delivery write fail (every record bounced through the retry tiers) and the analytics
   batch fail forever (retry-with-backoff never gives up), stalling the partition. Fixed with Flyway `V2`
   migrations in both services.
2. **Verifier assumptions.** It did not follow the analytics keyset cursor (saw only 50 rows), did not count
   `ACKNOWLEDGED` as success, and compared quota to the wrong number. Fixed.
3. **Hardcoded default quota of 1000.** Every load run was capped at 1000 requests per tenant (429 after).
   Now `TENANT_DEFAULT_QUOTA_LIMIT` (default unchanged, `-1` = unlimited).
4. **Synchronous log appender contention.** A thread dump under load showed 184 HTTP threads parked on one
   Logback `ReentrantLock` (DEBUG logging to the container console). Per-service `logging.level.*: DEBUG` in
   each `application.yml` overrode a generic override, so the fix is a per-service env var
   (`LOG_LEVEL`, default `WARN`). Accepted requests at 100 req/s target went from 647 to 2097 in the same
   45 s run.
5. **Memory headroom.** Gateway/notification/tenant ran at 98–99% of their container limit; limits raised.
6. **Cold gRPC channel.** The first request after gateway start returned 503 (deadline exceeded) because the
   channel to tenant-service was cold. Correct behavior for the new fail-fast policy, but needs a warm-up.

## 3. Measurements (single laptop, i5-1035G7 4C/8T, 15 GB RAM, load generator co-located)

Caveat that dominates everything below: during runs the host load average was **7.8–26 on 8 hardware
threads**. The full stack (5 JVMs, Kafka, Postgres, Redis, k6) plus other desktop processes oversubscribe the
CPU, so these are *this machine's* numbers, not the architecture's ceiling.

| Run | Result |
|---|---|
| Smoke (~10 req/s) | p50 33 ms, p95 67 ms, p99 157 ms, 0 errors; reconciliation PASS (373 accepted = 373 analytics = 373 notifications = quota 373) |
| Load, target 200 req/s, quota unlimited, early build | 999 accepted (first run capped by quota); reconciliation PASS (no loss, no duplicates) |
| Load, target 100 req/s, outbox | 4611 accepted over ~80 s, p50 4.8 s, p95 9.1 s; thresholds crossed |
| Load, target 100 req/s, kafka-first | 4162 accepted, p50 5.2 s, p95 10.0 s; thresholds crossed |
| Load, 100 req/s, after log-level fix | 2097 accepted in 45 s run (was 647), p50 6.3 s |
| Load, 100 req/s, 4 partitions instead of 12 | 1783 accepted, p50 6.5 s (no meaningful change) |

What these show and do not show:
- Sustained accepted throughput on this machine is roughly **40–60 req/s** with multi-second latency once the
  CPU saturates. Per-container CPU during load: Kafka 95–146%, notification-service 135–143%, gateway
  86–113%, delivery 58–93%, tenant 60%.
- **outbox vs kafka-first is inconclusive.** Both saturated the same shared CPU; the 11% difference in
  accepted requests is within what run-to-run noise on a contended host could produce. No conclusion is drawn.
- The 200 req/s target and the "as high as possible" goal were **not reached**. No true ceiling was measured.
- When a run was fully drained, reconciliation reported **no loss and no duplicate processing**. Rows
  exceeding k6's "accepted" count (5–85 per run) were requests that the server completed but the client had
  timed out on (10 s client timeout under saturation); recipients are unique per request and the duplicate
  recipient query returned 0.

## 4. Design decisions worth remembering

- **Charge on provider acceptance, no refund on bounce.** Delivery reports `SENT` when the provider accepts;
  a later bounce webhook only moves analytics to `FAILED`. Verifier checks quota against accepted sends.
- **Delivered/bounced use `ACKNOWLEDGED`/`FAILED` on the wire.** The shared Avro enum has no
  `DELIVERED`/`BOUNCED`; adding them is a schema change that was deferred.
- **Fail policies:** quota dependency down -> 503 (closed); rate limiter Redis down -> allow (open) with a
  metric; tenant-service unavailable at gateway -> 503, invalid key -> 401.

## 5. Not done / open risks (honest list)

- **Chaos tests were not run** (kill Kafka/Redis/Postgres/delivery mid-load). The scripts exist
  (`chaos/`, `make chaos`) but no fault run, so crash-recovery claims rest on design and unit tests only.
- **Mailpit/email path not exercised end to end.** All load used an HTTP channel; Mailpit received 0 mails.
- **Hot partition key.** `notification.requested` is keyed by `tenantId`; a single tenant lands on one
  partition (observed: all 228 smoke events on partition 9), capping one tenant to one consumer thread.
  Needs a key such as `tenantId:recipient-hash` or per-tenant fairness design.
- **Analytics batch retry never gives up.** A poison batch blocks its partition forever (this is how bug 1
  stalled). Needs per-record fallback and a dead-letter path.
- **No Prometheus/Grafana run, no soak, stress, spike or noisy-neighbor scenario was executed.**
- Delivery admin and webhook endpoints are unauthenticated (must not be routed through the gateway).
- No admin API for per-tenant idempotency mode; Micrometer metrics for delivery not added.
- Cold-start 503 on the first request after gateway start (needs channel warm-up).

## 6. Suggested next steps, in order of expected value

1. Re-run the matrix on an otherwise idle machine (or a larger box) with k6 on a separate host; only then
   compare outbox vs kafka-first and find the real ceiling.
2. Run the chaos matrix (`make chaos FAULT=...`) and record PASS/FAIL per fault.
3. Fix the hot partition key and the analytics poison-batch behavior.
4. Reduce delivery consumer thread count (lanes + retry tiers poll aggressively; Kafka burned 95–146% CPU at
   ~100 msg/s) and re-measure.
5. Exercise the Mailpit email channel and add a webhook-callback scenario to the verifier.

## 7. Reproduce

```bash
make up                       # core stack (+ -f compose.providers.yaml for the mock provider)
make smoke
./scripts/consistency.sh      # idempotency + exact quota under concurrency
export TENANT_DEFAULT_QUOTA_LIMIT=-1 GATEWAY_RATE_LIMIT_REPLENISH_RATE=100000 GATEWAY_RATE_LIMIT_BURST_CAPACITY=200000
make bench SCENARIO=load RATE=100 WARMUP=10s DURATION=45s
```
