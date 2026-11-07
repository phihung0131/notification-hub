# ADR 0010: Gateway authentication, caching and per-tenant rate limiting

Status: accepted

## Context

Findings on gateway-service:

- `AuthFilter` called a blocking gRPC stub inside a reactive filter (Netty event loop), with no deadline and
  no cache: one slow tenant-service stalled every request.
- Any error (including tenant-service being down) produced 401, telling healthy clients their key was bad.
- The rate limiter was configured at 50000/s replenish with burst 10000 (effectively unlimited, and
  replenish > burst), and its dependence on `X-Tenant-Id` set by `AuthFilter` was unverified. Clients could
  also send their own `X-Tenant-Id`.
- `ApiKeyAuthFilterTest` was entirely commented out.

## Decision

**Non-blocking validation.** `TenantValidationClient` runs the blocking stub on `Schedulers.boundedElastic()`
behind a `TenantRpc` seam (`GrpcTenantRpc` adds `withDeadlineAfter`, default 2s,
`GATEWAY_TENANT_GRPC_DEADLINE`).

**Cache.** Valid API keys are cached in Caffeine (`AsyncCache`, TTL 30s, max 10k;
`GATEWAY_AUTH_CACHE_TTL`). Concurrent lookups of one key coalesce into one RPC; failed lookups are never
cached. Consequence: a revoked key keeps working for at most the TTL; this is the explicit trade-off for taking
tenant-service off the hot path. Definitively invalid keys are cached negatively for 5s so key-guessing floods
do not reach tenant-service. JWTs are not cached.

**Error classification.** Only a definitive rejection (`isValid=false`, or gRPC `UNAUTHENTICATED`,
`PERMISSION_DENIED`, `NOT_FOUND`, `INVALID_ARGUMENT`) gives 401. Everything else (`UNAVAILABLE`,
`DEADLINE_EXCEEDED`, `INTERNAL`, unknown) gives 503 with `Retry-After: 1`. Auth therefore fails closed: with
tenant-service down and a cold cache requests are refused, but honestly (503). A cached valid key keeps
working during an outage for up to the TTL.

**Rate limiting.** Spring Cloud Gateway `RequestRateLimiter` (Redis token bucket, `redis-edge`) as a default
filter, key `tenant:<X-Tenant-Id>`; requests without a tenant (login/register) are keyed `anon:<client ip>` so
they cannot drain a tenant bucket. Rates are `GATEWAY_RATE_LIMIT_REPLENISH_RATE` (default 100/s) and
`GATEWAY_RATE_LIMIT_BURST_CAPACITY` (default 200), burst >= replenish. Exhausted bucket gives 429. Ordering is
explicit: `AuthFilter.ORDER = -100` runs before the limiter (default filters start at order 1), and
`AuthFilter` removes any client-sent `X-Tenant-Id`, `X-Permissions`, `X-Auth-Method` before routing, so the key
cannot be spoofed. Limits are per tenant, not per plan; plan-based tiers are future work.

**Redis failure policy: fail-open.** `MeteredRedisRateLimiter` bounds the Redis call with a timeout
(`GATEWAY_RATE_LIMIT_REDIS_TIMEOUT`, 200ms) and allows the request on timeout or error, incrementing the
Micrometer counter `gateway.ratelimit.failopen` (also incremented when the stock limiter swallows an error and
reports remaining tokens `-1`). Rationale: rate limiting protects capacity, not correctness; quota is enforced
exactly downstream. Alert on `rate(gateway_ratelimit_failopen_total) > 0`. During a Redis outage a noisy
neighbour is not throttled.

**Pass-through and timeouts.** The gateway adds identity headers only; `Idempotency-Key` and all other client
headers reach the backend untouched (covered by test). Routes use `connect-timeout` 2s and `response-timeout`
10s (`GATEWAY_CONNECT_TIMEOUT_MS`, `GATEWAY_RESPONSE_TIMEOUT`), so a stuck backend yields a gateway timeout
instead of an unbounded wait.

## Consequences

- Event loop is never blocked by auth; tenant-service load drops to roughly one call per key per TTL.
- Tests (unit, no Redis/gRPC server): 401 vs 503 vs 200 paths, header spoofing, header pass-through,
  429 with per-tenant isolation, fail-open and its metric, cache hit/expiry/no-cache-on-failure.
- Not verified here: behaviour against a live Redis and tenant-service (stack not started on this host);
  to be covered by the Testcontainers/compose chaos phase.
