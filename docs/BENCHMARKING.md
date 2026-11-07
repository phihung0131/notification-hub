# Benchmarking, verification and chaos

Tooling lives in `loadtest/` (k6), `verifier/` (reconciliation), `chaos/` (fault injection), `observability/` (Grafana/Prometheus) and `compose.loadtest.yaml` (overlay). Only Docker + Compose + `python3` and `jq` on the host are needed.

## Quick start

```bash
make up                                   # core stack
make bench SCENARIO=smoke                 # 1 VU, 10 s: proves wiring
make bench SCENARIO=load RATE=100 DURATION=3m
make verify                               # re-run reconciliation on the last run
make chaos FAULT=kafka                    # fault run + verifier, appends to reports/chaos-matrix.md
make report                               # docs/reports/<date>-<run>.md from TEMPLATE.md
make lt-obs                               # Prometheus + Grafana (:3000) with provisioned dashboards
```

Artifacts per run are written to `reports/<run-id>/` (git-ignored): `summary.json` (k6), `manifest.json`
(tenants, accepted counts, recipient prefixes), `verify.json|md`, `k6.log`, `docker-stats.csv`.

## What a run does

1. k6 `setup()` bootstraps a fresh tenant through public APIs: register, login, permission, API key (expiry now + 30 days, `KEY_TTL_DAYS`), channel. `noisy-neighbor` bootstraps two tenants.
2. Scenario traffic goes through `gateway-service` with `X-Api-Key`. Every request uses a unique recipient `lt-<run>-<tenant alias>-<vu>-<iter>@load.test`, so downstream rows and mails can be matched one to one.
3. `handleSummary` writes `manifest.json`: accepted (2xx) count per tenant, plus `expectedUnique` for the idempotency storm.
4. The verifier waits for drain, then reconciles.

## Scenarios (`SCENARIO=`)

| name | shape | main knobs |
|---|---|---|
| `smoke` | constant VUs | `VUS=1 DURATION=10s` |
| `load` | warm-up ramp to `RATE` then steady | `RATE=50 WARMUP=30s DURATION=2m` |
| `stress` | step ramp from `START_RATE` by `STEP_RATE` every `STEP_DURATION` until `MAX_RATE`; aborts when error rate > `STRESS_ERR_RATE` (5 %) | `START_RATE=20 STEP_RATE=20 MAX_RATE=600 STEP_DURATION=1m` |
| `spike` | base, jump to `SPIKE_RATE`, back to base, measure recovery | `RATE SPIKE_RATE SPIKE_DURATION RECOVER_DURATION` |
| `soak` | steady load for long time | `RATE SOAK_DURATION=1h` |
| `noisy-neighbor` | tenant `noisy` floods at `NOISY_RATE`; tenant `victim` sends `VICTIM_RATE`; thresholds only on victim | `NOISY_RATE=300 VICTIM_RATE=10 VICTIM_P95_MS` |
| `idempotency-storm` | each iteration is one `Idempotency-Key` sent `DUP` times concurrently (`http.batch`) | `KEYS_PER_SEC=10 DUP=20` |
| `burst` | each iteration fires `BATCH_SIZE` distinct requests in parallel | `BATCHES_PER_SEC=2 BATCH_SIZE=50` |

Thresholds (all scenarios except noisy-neighbor and stress): p95 < `P95_MS` (500), p99 < `P99_MS` (1500), `nh_errors` < `ERR_RATE` (1 %). HTTP 429 is counted as `nh_throttled`, not as an error: rate limiting is a correct response. k6 exits 99 when a threshold fails.
Arrival-rate executors are used (open model) so a slow system cannot hide by slowing the load generator. If k6 warns about dropped iterations, raise `MAX_VUS`.

Optional live k6 metrics in Grafana: `make lt-obs`, then `K6_OUT=experimental-prometheus-rw make bench SCENARIO=load`.

## Reconciliation verifier

`verifier/verify.py` (stdlib Python; runs on host by `make bench/verify/chaos`, or in a `python:3.12-slim` container via `docker compose -f compose.yaml -f compose.loadtest.yaml --profile loadtest run --rm verifier --run-id <id>`).

| check | compares | violation |
|---|---|---|
| `analytics` | analytics rows for the run's recipient prefix vs expected | lost, surplus, duplicate `messageId`, status not terminal after drain |
| `notifications` | `notifications` rows in `notification_db` vs expected, duplicate recipients | lost, surplus, same recipient twice |
| `mailpit` | mails vs analytics-successful recipients | duplicate mail per recipient (double send), success without mail, mail delivered but analytics says failed (regressed status) |
| `quota` | `tenants.quota_used` vs successful sends | drift (lost or double-counted saga commit) |

`expected` = accepted count from k6, or number of distinct keys for the idempotency storm (so 20 duplicates of one key must still yield exactly 1 notification and 1 mail).

Exit code is 1 on any FAIL. A backend that cannot be reached gives SKIPPED (not PASS); if every check is skipped the verdict is INCONCLUSIVE (exit 1). `STRICT=1` turns SKIPPED into FAIL. Select checks with `CHECKS=analytics,mailpit`. `make bench` clears Mailpit first (`KEEP_MAIL=1` to keep).

### Assumptions (all isolated in `verifier/adapters.py`)

- Analytics: `GET http://localhost:9004/api/v1/analytics/messages` with `X-Tenant-Id`, returning a list (possibly inside `ApiResponse`) of `{messageId, recipient, status}`. Override `ANALYTICS_URL`, `ANALYTICS_LIST_PATH`.
- Mailpit: `/api/v1/messages?start&limit` and `DELETE /api/v1/messages`.
- Notification rows and quota have no read API, so they are read by SQL through `docker compose exec postgres psql` (host mode only; skipped inside the verifier container). Table/column names are overridable (`SQL_NOTIFICATIONS`, `SQL_DUP_RECIPIENTS`, `SQL_QUOTA`, `PSQL_CMD`).
- Status vocabulary: success `SENT,DELIVERED,SUCCESS,COMPLETED` (`SUCCESS_STATUSES`), failed `FAILED,DLQ,REJECTED` (`FAILED_STATUSES`); anything else is non-terminal.
- Tenant ID comes from the register response (`id`), falling back to the internal API-key validate endpoint.
- Quota is checked only after drain, because quota commits are eventually consistent (saga).
- If the delivery path uses a mock instead of SMTP, the mailpit check will report "analytics-successful but no mail"; run with `CHECKS=analytics,notifications,quota` in that case.

## Chaos

```bash
make chaos FAULT=kafka                      # one fault
make chaos FAULT=kafka,redis-core,postgres,delivery,tenant FAULT_MODE=kill
make chaos FAULT=delivery FAULT_MODE=pause  # docker pause instead of kill
make chaos FAULT=kafka FAULT_AT=20 HEAL_AT=50 RATE=50
```

`chaos/run.sh` for each fault: clears Mailpit, starts a k6 `load` run (thresholds relaxed, since failures during the fault are expected), injects the fault at T+`FAULT_AT` (after warm-up), heals at T+`HEAL_AT`, keeps loading `TAIL` seconds, settles, then runs the verifier (waits up to `DRAIN_TIMEOUT`) and appends a PASS/FAIL row to `reports/chaos-matrix.md`.
PASS means: no lost accepted request, no duplicate mail, no regressed status, quota equals successful sends, all reached terminal state after recovery without manual action.

Container faults use `docker compose kill|start` or `pause|unpause` (`chaos/fault.sh`). Network faults use Toxiproxy (`chaos/toxiproxy.sh`): start it with `make lt-chaos-up`, create a proxy, route the client through it (instructions in the script header), then `FAULT=latency:redis-core make chaos` (`LATENCY_MS`, `JITTER_MS`), `loss:<proxy>` (`LOSS_PCT`, implemented as probabilistic timeouts) or `cut:<proxy>`. Toxiproxy is not in the data path unless you re-point a dependency; Kafka needs advertised listeners that point back to the proxy, so use kill/pause for Kafka.

Note: while the gateway cannot reach a dependency it may answer 5xx/429/timeouts. Those are not "accepted" and are not counted in the reconciliation; only 2xx responses create a promise that must be honoured.

## Observability

`compose.loadtest.yaml` overrides `prometheus` (config `observability/prometheus.yml`, remote-write receiver enabled) and `grafana` (provisioned datasource + dashboards). `docker/prometheus.yml` is unchanged.

Dashboards (`observability/grafana/dashboards`):
- `overview.json`: RPS, error rate, p50/p95/p99 (`http_server_requests_seconds`), 4xx rate, JVM heap/GC/threads/CPU, Hikari, Redis (Lettuce), Kafka lag.
- `k6.json`: k6 RPS, VUs, latency percentiles, error and throttle rates (only with remote write).

Kafka consumer lag: client-side `kafka_consumer_fetch_manager_records_lag_max` appears only if Micrometer Kafka metrics are bound; for real group lag start the exporter: `docker compose -f compose.yaml -f compose.loadtest.yaml --profile observability --profile observability-extra up -d kafka-exporter` (64 MB). Lettuce panels need Lettuce metrics enabled in the services; panels stay empty otherwise. No Redis/Postgres exporters are shipped.

## Methodology

- Single machine (4 cores / 8 threads, shared by the whole stack, Kafka, Postgres, Redis and k6). Absolute numbers are only comparable between runs on the same machine under the same conditions. Close other heavy apps.
- The load generator is co-located and steals CPU from the system under test. Keep k6 modest (arrival-rate executors are cheap), cap its memory (512 MB set), and watch k6's own CPU in `docker-stats.csv`. If k6 CPU is high or dropped iterations appear, the result is generator-bound, not service-bound.
- Warm-up: every scenario starts with a ramp (`WARMUP`) so JIT, connection pools and caches stabilise. Ignore the first minute when reading percentiles; for decisions run at least 3 minutes steady.
- Use a fresh tenant per run (automatic) and clear Mailpit; reset volumes (`make clean`) for baseline runs after schema changes.
- Memory is tight (about 4 GB free in the reference setup): do not run `soak` together with Grafana/Jaeger unless needed; run the observability profile only when you need graphs.

### Reading results

1. `nh_errors` and `nh_throttled`: real failures versus rate limiting.
2. p95/p99 versus thresholds and, more importantly, versus the previous run. The mean hides tail latency.
3. Verifier verdict: a fast run that loses messages is a failed run.
4. Grafana: which service saturates first (CPU, Hikari pending > 0, GC share, Kafka lag growing) while RPS flattens.

### Finding the ceiling

1. Run `stress` (`START_RATE=20 STEP_RATE=20 MAX_RATE=800`). Each step holds `STEP_DURATION`.
2. The ceiling is the last step before p95 breaks the threshold or the error rate rises, read from the k6 dashboard or from timestamps in `k6.log` against the step schedule.
3. Confirm with `load` at 80 % of that rate for 5 minutes: it must pass thresholds and the verifier. That rate is the sustainable throughput.
4. Look for the first saturated resource, change one thing (partitions, pool size, heap, batch size), repeat, and record before/after in `docs/reports/`.
5. Use `spike` at 5 to 10 times the sustainable rate to check recovery, and `soak` at 60 to 70 % for 1 hour to find leaks.
