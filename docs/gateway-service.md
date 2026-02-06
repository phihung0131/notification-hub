# Gateway Service - Technical Specification

**Version:** 1.0
**Port:** 9000 (HTTP)
**Tech Stack:** Spring Cloud Gateway, Spring WebFlux, Redis
**Repository:** `gateway-service/`

---

## Overview

Gateway Service là single entry point cho tất cả external client requests đến Notification Hub platform. Nó cung cấp centralized authentication, authorization, rate limiting, và request routing đến các backend microservices.

**Key Characteristics:**
- ✅ **Reactive Architecture** - Spring Cloud Gateway với WebFlux for non-blocking I/O
- ✅ **Stateless** - No database, chỉ sử dụng Redis cho rate limiting
- ✅ **High Performance** - Minimal latency overhead (<10ms)
- ✅ **Security First** - All requests authenticated trước khi forward

---

## Core Responsibilities

### 1. API Key Authentication

**Filter:** `ApiKeyAuthFilter` (order: -1, runs early)

**Flow:**
```
1. Extract Bearer token from Authorization header
2. Call Tenant Service REST API to validate API key
3. On success: Inject X-Tenant-Id & X-Permissions headers
4. Forward request to backend service
5. On failure: Return 401 Unauthorized
```

**Whitelisted Paths** (no authentication required):
- `/actuator/**` - Health checks và metrics
- `/api/v1/tenants/auth/register` - Tenant registration
- `/api/v1/tenants/auth/login` - Tenant login

**Implementation:**
```java
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Extract API key from Authorization: Bearer <key>
        // Validate with Tenant Service
        // Inject X-Tenant-Id header
        // Forward request
    }
}
```

**Reference:** `gateway-service/src/main/java/org/example/gatewayservice/filter/ApiKeyAuthFilter.java:21`

---

### 2. Request Routing

**Configuration:** `GatewayConfig.java`

**Routes:**

| Path Pattern | Target Service | Port |
|-------------|----------------|------|
| `/api/v1/tenants/**` | tenant-service | 9001 |
| `/api/v1/notifications/**` | notification-service | 9002 |
| `/api/v1/analytics/**` | analytics-service | 9004 |

**Example Route Configuration:**
```java
@Bean
public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
    return builder.routes()
        .route("tenant-service", r -> r
            .path("/api/v1/tenants/**")
            .uri("http://tenant-service:8080"))
        .route("notification-service", r -> r
            .path("/api/v1/notifications/**")
            .uri("http://notification-service:8080"))
        .build();
}
```

---

### 3. Rate Limiting

**Implementation:** Redis-backed Token Bucket algorithm

**Configuration:**
- **Replenish Rate:** 50 requests/second
- **Burst Capacity:** 100 requests
- **Scope:** Per tenant (based on X-Tenant-Id)

**Redis Keys:**
- `rate_limit:{tenantId}:tokens` - Available tokens
- `rate_limit:{tenantId}:timestamp` - Last refill time

**Behavior:**
- Requests within limit: Processed normally
- Requests exceeding limit: **429 Too Many Requests** với `Retry-After` header

---

### 4. Header Injection

**Injected Headers:**

| Header | Source | Purpose |
|--------|--------|---------|
| `X-Tenant-Id` | From API key validation | Tenant identification cho backend services |
| `X-Permissions` | From API key validation | Comma-separated permissions (e.g., "SEND_NOTIFICATION,VIEW_ANALYTICS") |

**Example:**
```
Original Request:
  POST /api/v1/notifications/send
  Authorization: Bearer sk_live_abc123...

After Gateway Processing:
  POST /api/v1/notifications/send
  Authorization: Bearer sk_live_abc123...
  X-Tenant-Id: tenant-uuid-123
  X-Permissions: SEND_NOTIFICATION,VIEW_ANALYTICS
```

---

## API Endpoints

### External (Public)

Gateway exposes tất cả backend service endpoints thông qua routing.

**Base URL:** `http://localhost:9000` (development)

**Authentication:** Required for all endpoints except whitelisted paths
```
Authorization: Bearer <API_KEY>
```

---

### Internal (Service-to-Service)

**Tenant Validation:**
```
GET http://tenant-service:8080/auth/internal/apikeys/validate?apiKey={key}

Response:
{
  "tenantId": "tenant-uuid-123",
  "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"],
  "status": "ACTIVE",
  "plan": "FREE"
}
```

**Called by:** `TenantValidationClient`
**Reference:** `gateway-service/src/main/java/org/example/gatewayservice/service/TenantValidationClient.java:11`

---

## Configuration

### Environment Variables

```bash
# Spring Application
SPRING_APPLICATION_NAME=gateway-service

# Redis (for rate limiting)
SPRING_DATA_REDIS_HOST=gateway-redis
SPRING_DATA_REDIS_PORT=6379

# Tenant Service (for API key validation)
TENANT_SERVICE_URL=http://tenant-service:8080

# Logging
LOGGING_LEVEL_ORG_EXAMPLE_GATEWAYSERVICE=INFO

# OpenTelemetry
OTEL_SERVICE_NAME=gateway-service
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
```

### application.yml

```yaml
spring:
  application:
    name: gateway-service

  cloud:
    gateway:
      routes:
        - id: tenant-service
          uri: ${TENANT_SERVICE_URL:http://localhost:9001}
          predicates:
            - Path=/api/v1/tenants/**

        - id: notification-service
          uri: ${NOTIFICATION_SERVICE_URL:http://localhost:9002}
          predicates:
            - Path=/api/v1/notifications/**

        - id: analytics-service
          uri: ${ANALYTICS_SERVICE_URL:http://localhost:9004}
          predicates:
            - Path=/api/v1/analytics/**

  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
```

**Reference:** `gateway-service/src/main/resources/application.yml`

---

## Dependencies

### Required Services

1. **Tenant Service** (HTTP: 9001)
   - Purpose: API key validation
   - Endpoint: `GET /auth/internal/apikeys/validate`
   - Critical: Gateway cannot function without this

2. **Redis** (Port: 6379)
   - Purpose: Rate limiting state
   - Behavior: If unavailable, rate limiting disabled (fail-open)

### Optional Services

All backend services are optional - Gateway routes to them but doesn't depend on them for startup.

---

## Data Flow

```
┌─────────┐                                 ┌─────────────┐
│ Client  │────1. POST /send──────────────▶│   Gateway   │
└─────────┘    Authorization: Bearer sk... └─────────────┘
                                                    │
                                     2. Validate API Key
                                                    ▼
                                            ┌───────────────┐
                                            │Tenant Service │
                                            │ (REST API)    │
                                            └───────────────┘
                                                    │
                                     3. Valid (tenantId, permissions)
                                                    ▼
                                            ┌─────────────┐
                                            │   Gateway   │
                                            │ Inject      │
                                            │ Headers     │
                                            └─────────────┘
                                                    │
                                     4. Forward Request
                                                    ▼
                                        ┌──────────────────────┐
                                        │ Notification Service │
                                        └──────────────────────┘
```

---

## Error Handling

### Authentication Errors

**Missing Authorization Header:**
```http
HTTP/1.1 401 Unauthorized
X-Error: Missing Authorization Bearer token
```

**Invalid API Key:**
```http
HTTP/1.1 401 Unauthorized
X-Error: Invalid API key
```

**Revoked API Key:**
```http
HTTP/1.1 401 Unauthorized
X-Error: API key has been revoked
```

---

### Rate Limiting Errors

**Too Many Requests:**
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 5
X-RateLimit-Remaining: 0
X-RateLimit-Limit: 100

{
  "error": "Rate limit exceeded",
  "retryAfter": 5
}
```

---

### Service Unavailable

**Tenant Service Down:**
```http
HTTP/1.1 503 Service Unavailable

{
  "error": "Unable to validate API key - tenant service unavailable"
}
```

**Behavior:** Gateway implements **fail-open** strategy - if Tenant Service unavailable, requests may be allowed through (logged for audit).

---

## Performance Characteristics

### Latency

- **Target:** <10ms overhead
- **Breakdown:**
  - API key extraction: <1ms
  - Tenant validation (Redis cached): 2-5ms
  - Header injection: <1ms
  - Routing: 2-3ms

### Throughput

- **Capacity:** 10,000+ requests/second per instance
- **Bottleneck:** Redis round-trip for rate limiting
- **Scaling:** Horizontal scaling supported (stateless)

### Caching

**API Key Validation Cache:**
- **Key:** `apikey:{key}`
- **Value:** `{tenantId, permissions, status}`
- **TTL:** 5 minutes
- **Hit Rate Target:** >95%

---

## Monitoring & Observability

### Health Check

```bash
GET http://localhost:9000/actuator/health

Response:
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP"},
    "diskSpace": {"status": "UP"}
  }
}
```

### Metrics (Prometheus)

```bash
GET http://localhost:9000/actuator/prometheus
```

**Key Metrics:**
- `gateway_requests_total` - Total requests processed
- `gateway_requests_duration_seconds` - Request latency histogram
- `gateway_auth_failures_total` - Authentication failures
- `gateway_rate_limit_exceeded_total` - Rate limit violations
- `gateway_redis_connection_errors_total` - Redis connectivity issues

### Distributed Tracing

**Integration:** OpenTelemetry
**Trace ID:** Injected into all responses
**Spans:**
- `gateway.auth` - API key validation
- `gateway.route` - Request routing
- `gateway.rate_limit` - Rate limit check

---

## Security Considerations

### API Key Security

- ✅ **Never logged** - API keys are masked in logs
- ✅ **HTTPS only** in production
- ✅ **Short-lived validation cache** (5 min TTL)
- ✅ **Revocation support** - Cached keys expire quickly

### Multi-Tenancy Isolation

- ✅ **X-Tenant-Id header** - Injected by Gateway, trusted by all services
- ✅ **Tamper-proof** - Backend services should NOT trust client-provided X-Tenant-Id
- ✅ **Gateway is authority** - Only Gateway can set X-Tenant-Id

### Rate Limiting Security

- ✅ **Per-tenant limits** - Prevents one tenant from exhausting resources
- ✅ **DDoS protection** - Burst capacity limits sudden spikes
- ✅ **Fail-safe** - If Redis down, falls back to in-memory limits

---

## Deployment

### Docker Compose (Development)

```bash
docker compose -f docker/docker-compose.gateway-dev.yml up --watch
```

**Services:**
- gateway-service (port 9000)
- gateway-redis (port 6000)

### Environment

**Required:**
- `TENANT_SERVICE_URL` - Tenant service base URL
- `SPRING_DATA_REDIS_HOST` - Redis hostname

**Optional:**
- `GATEWAY_RATE_LIMIT_REPLENISH_RATE` - Default: 50
- `GATEWAY_RATE_LIMIT_BURST_CAPACITY` - Default: 100

---

## Testing

### Unit Tests

**Test Class:** `ApiKeyAuthFilterTest`
**Coverage:** 12 tests, 100% coverage
**Location:** `gateway-service/src/test/java/.../filter/ApiKeyAuthFilterTest.java`

**Test Scenarios:**
- Whitelisted paths bypass authentication
- Missing Authorization header → 401
- Invalid API key format → 401
- Valid API key → inject headers và forward
- Tenant service error → 401

**Run Tests:**
```bash
cd gateway-service
mvn test -Dtest=ApiKeyAuthFilterTest
```

---

## Troubleshooting

### Gateway Won't Start

**Check Redis:**
```bash
docker ps | grep gateway-redis
redis-cli -h localhost -p 6000 ping
```

**Check Tenant Service:**
```bash
curl http://localhost:9001/actuator/health
```

### API Key Validation Failing

**Enable debug logging:**
```yaml
logging:
  level:
    org.example.gatewayservice: DEBUG
```

**Check logs:**
```bash
docker logs gateway-service --tail 100 | grep "API key validation"
```

### Rate Limiting Not Working

**Check Redis connection:**
```bash
docker logs gateway-service | grep "Redis"
```

**Verify rate limit config:**
```bash
curl http://localhost:9000/actuator/configprops | jq .
```

---

## Best Practices

### 1. Always Use Gateway in Production

❌ **Don't:** Direct access to backend services
✅ **Do:** All requests through Gateway

### 2. Monitor Rate Limits

```bash
# Check Redis for tenant rate limits
redis-cli -h localhost -p 6000
> KEYS rate_limit:*
> GET rate_limit:tenant-123:tokens
```

### 3. Cache Warming

Gateway caches API key validations for 5 minutes. Newly created keys may take up to 5 minutes to be usable.

### 4. Graceful Degradation

If Tenant Service is down, Gateway may allow requests through (fail-open). Monitor health checks closely.

---

## Related Documentation

- **API Usage Guide:** [API_USAGE.md](API_USAGE.md)
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Troubleshooting:** [TROUBLESHOOTING.md](TROUBLESHOOTING.md)

---

**Last Updated:** 2025-12-15
**Maintainer:** Notification Hub Team
