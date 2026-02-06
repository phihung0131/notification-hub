# Tenant Service - Technical Specification

**Version:** 1.0
**Ports:** 9001 (HTTP), 5001 (gRPC)
**Tech Stack:** Spring Boot, PostgreSQL, Redis, Kafka, gRPC
**Repository:** `tenant-service/`

---

## Overview

Tenant Service là authoritative source cho tenant identities, authentication, authorization, và quota management. Nó implements **Saga Pattern** cho accurate quota tracking với eventual consistency.

**Key Characteristics:**
- ✅ **Multi-Protocol** - REST API (public) + gRPC (internal high-performance)
- ✅ **Dual-Mode Auth** - JWT tokens (web) + API keys (programmatic)
- ✅ **Saga Pattern** - Quota chỉ increment khi delivery thành công
- ✅ **High Performance** - Redis caching cho API key validation
- ✅ **Batch Processing** - Kafka events processed in batches (50 events hoặc 5 seconds)

---

## Core Responsibilities

### 1. Tenant Management

**Database Table:** `tenants`

**Schema:**
```sql
CREATE TABLE tenants (
    id                VARCHAR(255) PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    email             VARCHAR(255) UNIQUE NOT NULL,
    password          VARCHAR(255) NOT NULL,  -- BCrypt hashed
    plan              VARCHAR(50) NOT NULL,   -- FREE, PRO, ENTERPRISE
    status            VARCHAR(50) NOT NULL,   -- ACTIVE, SUSPENDED, INACTIVE
    quota_limit       INTEGER NOT NULL,       -- -1 = unlimited
    quota_used        INTEGER DEFAULT 0,      -- Incremented by Saga
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);
```

**API Endpoints:**

**Registration:**
```http
POST /api/v1/tenants/auth/register
Content-Type: application/json

{
  "name": "Acme Corp",
  "email": "admin@acme.com",
  "password": "SecurePass123!"
}

Response: 200 OK
{
  "success": true,
  "data": {
    "id": "tenant-uuid-123",
    "name": "Acme Corp",
    "email": "admin@acme.com",
    "plan": "FREE",
    "quotaLimit": 1000,
    "quotaUsed": 0
  }
}
```

**Login:**
```http
POST /api/v1/tenants/auth/login
Content-Type: application/json

{
  "email": "admin@acme.com",
  "password": "SecurePass123!"
}

Response: 200 OK
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "type": "Bearer",
    "expiresIn": 86400000
  }
}
```

---

### 2. API Key Management

**Database Table:** `api_keys`

**Schema:**
```sql
CREATE TABLE api_key (
    id              VARCHAR(255) PRIMARY KEY,
    tenant_id       VARCHAR(255) REFERENCES tenants(id),
    api_key_value   VARCHAR(255) UNIQUE NOT NULL,  -- sk_live_... or sk_test_...
    revoked         BOOLEAN DEFAULT FALSE,
    expired_at      TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE apikey_permissions (
    apikey_id      VARCHAR(255) REFERENCES api_key(id),
    permission_id  VARCHAR(255) REFERENCES permission(id),
    PRIMARY KEY (apikey_id, permission_id)
);
```

**API Endpoints:**

**Create API Key:**
```http
POST /api/v1/tenants/apikeys
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "name": "Production Key",
  "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"]
}

Response: 201 Created
{
  "success": true,
  "data": {
    "id": "key-uuid-456",
    "key": "sk_live_abc123def456...",  // ⚠️ Shown only once!
    "name": "Production Key",
    "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"],
    "revoked": false
  }
}
```

**List API Keys:**
```http
GET /api/v1/tenants/apikeys
Authorization: Bearer <JWT_TOKEN>

Response: 200 OK
{
  "success": true,
  "data": [
    {
      "id": "key-uuid-456",
      "name": "Production Key",
      "revoked": false,
      "createdAt": "2024-01-15T10:00:00Z"
    }
  ]
}
```

**Revoke API Key:**
```http
DELETE /api/v1/tenants/apikeys/{keyId}
Authorization: Bearer <JWT_TOKEN>

Response: 200 OK
{
  "success": true,
  "data": {
    "revoked": true,
    "revokedAt": "2024-01-15T11:00:00Z"
  }
}
```

---

### 3. High-Performance gRPC Endpoints

**Proto File:** `commons-shared/src/main/proto/tenant.proto`

#### 3.1 ValidateApiKey

**Called by:** Gateway Service

**Request:**
```protobuf
message ValidateApiKeyRequest {
  string apiKey = 1;
}
```

**Response:**
```protobuf
message ValidateApiKeyResponse {
  bool isValid = 1;
  string tenantId = 2;
  string status = 3;  // ACTIVE, SUSPENDED
  string plan = 4;    // FREE, PRO
}
```

**Caching:** Results cached in Redis với TTL 5 phút
**Cache Key:** `apiKeyPermissions:{apiKey}`

#### 3.2 CheckQuota

**Called by:** Notification Service

**Request:**
```protobuf
message CheckQuotaRequest {
  string tenantId = 1;
}
```

**Response:**
```protobuf
message CheckQuotaResponse {
  bool isAllowed = 1;
  int32 remaining = 2;
  int32 limit = 3;
}
```

**Logic:**
```java
boolean hasQuota = (quotaUsed < quotaLimit) || (quotaLimit == -1);  // -1 = unlimited
int remaining = (quotaLimit == -1) ? -1 : (quotaLimit - quotaUsed);
```

**⚠️ Important:** This is **preliminary check only** - actual quota increment happens via Saga pattern!

---

### 4. Quota Management (Saga Pattern)

**Pattern:** Eventual Consistency - quota incremented ONLY after successful delivery

**Kafka Consumer:**
- **Topic:** `notification.result`
- **Consumer Group:** `tenant-consumer-group`
- **Batch Processing:** 50 events hoặc 5 seconds (whichever first)

**Flow:**
```
1. Notification sent → quota NOT incremented yet
2. Delivery Service attempts delivery
3. On SUCCESS:
   - Delivery publishes { status: "SENT" } to notification.result
   - Tenant Service consumes event
   - Increments quota: UPDATE tenants SET quota_used = quota_used + 1
4. On FAILURE:
   - Delivery publishes { status: "FAILED" }
   - Tenant Service consumes event
   - NO quota increment (compensation logic)
```

**Why Saga?**
- ✅ Fair billing - Only charge for successful deliveries
- ✅ Resilient - Works even if services temporarily down
- ✅ Scalable - Async processing không block API requests

**Implementation:** `tenant-service/src/main/java/org/example/tenantservice/kafka/consumer/NotificationResultConsumer.java`

**Idempotency:**
```sql
CREATE TABLE processed_events (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT NOW()
);
```

Prevents duplicate quota increments for same event.

---

## Configuration

### Environment Variables

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://tenant-db:5432/tenant_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# Redis
SPRING_DATA_REDIS_HOST=tenant-redis
SPRING_DATA_REDIS_PORT=6379

# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL=http://schema-registry:8081
SPRING_KAFKA_CONSUMER_GROUP_ID=tenant-consumer-group

# gRPC
GRPC_SERVER_PORT=50001

# JWT
JWT_SECRET=your-secret-key-min-256-bits
JWT_EXPIRATION=86400000  # 24 hours

# Batch Processing
APP_BATCH_SIZE=50
APP_BATCH_FLUSH_INTERVAL=5000  # milliseconds
```

### Kafka Topics

**Consumed:**
- `notification.result` - Delivery outcomes for quota Saga

**Published:**
- None (this service only consumes)

---

## Data Flow

### Authentication Flow

```
1. Client → Gateway: Login request
2. Gateway → Tenant: POST /auth/login
3. Tenant: Validate credentials (BCrypt)
4. Tenant: Generate JWT token
5. Tenant → Client: Return JWT
6. Client uses JWT for tenant management operations
```

### API Key Validation Flow

```
1. Gateway → Tenant (gRPC): ValidateApiKey(key)
2. Tenant: Check Redis cache
3. If cache miss:
   - Query database for API key
   - Check if revoked
   - Get tenant info & permissions
   - Store in Redis (TTL: 5 min)
4. Tenant → Gateway: {valid, tenantId, permissions}
5. Gateway: Inject X-Tenant-Id header
```

### Quota Saga Flow

```
Time 0s: Notification sent
  → Preliminary check: hasQuota = true
  → quota_used = 100 (unchanged yet)

Time 2s: Delivery succeeds
  → Publish to notification.result: {status: SENT}

Time 3s: Tenant Service consumes
  → Process batch (up to 50 events)
  → Increment quota: quota_used = 101 ✅
  → Mark event as processed (idempotency)

Result: Fair billing, eventual consistency
```

---

## Performance Characteristics

### Latency

**gRPC Endpoints:**
- ValidateApiKey: <5ms (Redis cached: <2ms)
- CheckQuota: <3ms (Redis + DB)

**REST Endpoints:**
- Registration: ~50ms (DB write + password hashing)
- Login: ~30ms (DB query + JWT generation)
- API Key creation: ~40ms (DB write + UUID generation)

### Throughput

- **gRPC:** 10,000+ requests/second
- **REST:** 1,000+ requests/second
- **Kafka Consumer:** 1,000+ events/second (batched)

### Caching

**API Key Validation:**
- Cache hit rate: >95% (5 min TTL)
- Redis keys: `apiKeyPermissions:{key}`

**Quota Check:**
- Cached from DB, refreshed on Saga updates

---

## Monitoring & Observability

### Health Check

```bash
GET http://localhost:9001/actuator/health
```

### Metrics

**Key Metrics:**
- `tenant_registrations_total` - Total registrations
- `tenant_logins_total` - Successful logins
- `tenant_quota_increments_total` - Saga completions
- `tenant_api_key_validations_total` - Cache hit/miss ratio
- `tenant_kafka_consumer_lag` - Consumer lag

### Logs

**Important Events:**
```
INFO  - Tenant registered: tenantId=xxx
INFO  - JWT generated for tenant: tenantId=xxx
INFO  - API key created: keyId=xxx, tenantId=xxx
INFO  - Saga: Incrementing quota for tenant-123, event: event-456
WARN  - API key revoked: keyId=xxx
ERROR - Kafka consumer error: ...
```

---

## Security Considerations

### Password Security

- ✅ **BCrypt hashing** - Strength: 12 rounds
- ✅ **Never logged** - Passwords masked in logs
- ✅ **Validation** - Min 8 characters, complexity requirements

### API Key Security

- ✅ **Prefix convention:** `sk_live_` (production), `sk_test_` (development)
- ✅ **Random generation:** Cryptographically secure UUID
- ✅ **Revocation support** - Instant via database flag
- ✅ **Expiration** - Optional expiry date

### JWT Security

- ✅ **HS256 signing** - Symmetric key algorithm
- ✅ **Expiration** - 24 hours default
- ✅ **Claims:** `sub` (tenant ID), `email`, `iat`, `exp`

---

## Testing

### Unit Tests

**Coverage:**
- AuthService: 13 tests
- TenantGrpcService: 11 tests
- QuotaService: 11 tests
- ApiKeyService: 12 tests
- Total: 36+ tests

**Run Tests:**
```bash
cd tenant-service
mvn test
```

**Test Configuration:** `tenant-service/src/test/resources/application-test.yml`

---

## Troubleshooting

### Quota Not Incrementing

**Diagnosis:**
```sql
-- Check quota
SELECT id, email, quota_limit, quota_used FROM tenants WHERE id = 'tenant-123';

-- Check processed events
SELECT COUNT(*) FROM processed_events WHERE event_id LIKE 'event-%';
```

**Common Causes:**
1. Kafka consumer not running
2. Delivery Service not publishing to notification.result
3. Events with status != "SENT"

**Fix:**
```bash
# Check consumer logs
docker logs tenant-service | grep "Saga: Incrementing"

# Check Kafka lag
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group tenant-consumer-group
```

### API Key Validation Slow

**Check Redis:**
```bash
redis-cli -h localhost -p 6001
> KEYS apiKeyPermissions:*
> TTL apiKeyPermissions:sk_live_abc123
```

**Clear cache if needed:**
```bash
redis-cli -h localhost -p 6001 FLUSHDB
```

---

## Related Documentation

- **Authentication Guide:** [API_USAGE.md#authentication-flow](API_USAGE.md#authentication-flow)
- **Quota Saga Pattern:** [ARCHITECTURE.md#quota-saga](ARCHITECTURE.md)
- **gRPC API:** `commons-shared/src/main/proto/tenant.proto`

---

**Last Updated:** 2025-12-15
**Maintainer:** Notification Hub Team
