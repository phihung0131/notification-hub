# Notification Service - Technical Specification

**Version:** 1.0
**Ports:** 9002 (HTTP), 50002 (gRPC)
**Tech Stack:** Spring Boot, PostgreSQL, Redis, Kafka, gRPC
**Repository:** `notification-service/`

---

## Overview

Notification Service là **fast API entry point** cho notification requests. Mục tiêu chính: **Accept requests và respond trong <50ms**, delegate actual delivery to background workers.

**Key Characteristics:**
- ✅ **Fast Response** - <50ms target (202 ACCEPTED immediately)
- ✅ **Asynchronous** - Publishes to Kafka, không wait for delivery
- ✅ **Facade Pattern** - Orchestrates validation, quota check, persistence, publishing
- ✅ **Fail-Fast** - Validates request và quota trước khi accept
- ✅ **Transactional** - Database save + Kafka publish in single transaction

---

## Core Responsibilities

### 1. API Entry Point

**Endpoint:** `POST /send`

**Required Headers:**
- `Authorization: Bearer <API_KEY>` - Validated by Gateway
- `X-Tenant-Id: <tenant-id>` - Injected by Gateway

**Request Body:**
```json
{
  "channel": "EMAIL",           // EMAIL, SMS, TELEGRAM
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Hello, thank you for signing up!"
}
```

**Response (202 ACCEPTED):**
```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "tenant-uuid-123",
    "notificationStatus": "PENDING",
    "message": "Notification request accepted and queued for processing"
  },
  "traceId": "a1b2c3d4e5f6...",
  "ts": "2024-01-15T10:30:00Z"
}
```

**Implementation:** `notification-service/src/main/java/org/example/notificationservice/controller/NotificationController.java:34`

---

### 2. Request Orchestration (Facade Pattern)

**Service:** `NotificationOrchestrationService`

**Flow:**
```
1. Validate Request
   └─> NotificationValidationService.validateRequest()
   └─> Checks: channel exists, recipient format, content not empty

2. Check Quota (Preliminary)
   └─> QuotaCheckService.hasAvailableQuota(tenantId)
   └─> gRPC call to Tenant Service
   └─> If no quota: throw QUOTA_EXCEEDED (429)

3. Get Channel
   └─> ChannelRepository.findByCode(channel)
   └─> If not found: throw CHANNEL_NOT_FOUND (404)

4. Save Notification (PENDING status)
   └─> NotificationRepository.save()
   └─> Database: notifications table

5. Publish to Kafka
   └─> NotificationPublisherService.publishNotification()
   └─> Topic: notification.requested
   └─> Key: notification.id (UUID)

6. Return Response (202 ACCEPTED)
   └─> Includes messageId for status tracking
```

**Transaction Boundary:** Steps 4-5 trong single @Transactional
**Rollback:** If Kafka publish fails, database save is rolled back

**Implementation:** `notification-service/src/main/java/org/example/notificationservice/service/NotificationOrchestrationService.java:47`

---

### 3. Data Persistence

**Database Table:** `notifications`

**Schema:**
```sql
CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    channel_id   VARCHAR(255) REFERENCES channels(id),
    recipient    VARCHAR(255) NOT NULL,
    subject      VARCHAR(500),
    content      TEXT NOT NULL,
    status       VARCHAR(50) NOT NULL,  -- PENDING, SENT, FAILED
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW(),
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
);
```

**Supported Channels Table:**
```sql
CREATE TABLE channels (
    id      VARCHAR(255) PRIMARY KEY,
    code    VARCHAR(50) UNIQUE NOT NULL,  -- email, sms, telegram
    name    VARCHAR(100) NOT NULL,
    active  BOOLEAN DEFAULT TRUE
);

-- Initial data
INSERT INTO channels (id, code, name) VALUES
  (gen_random_uuid(), 'EMAIL', 'Email'),
  (gen_random_uuid(), 'SMS', 'SMS'),
  (gen_random_uuid(), 'TELEGRAM', 'Telegram');
```

---

### 4. Kafka Event Publishing

**Topic:** `notification.requested`

**Message Format (Avro):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "tenant-uuid-123",
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Hello, thank you...",
  "status": "PENDING",
  "createdAt": 1705319400000
}
```

**Avro Schema:** `commons-shared/src/main/avro/NotificationEvent.avsc`

**Key Strategy:** Uses notification ID as Kafka message key
- Ensures ordering per notification
- Enables partition-based parallelism

**Consumers:**
- Delivery Service - Processes delivery
- Analytics Service - Tracks lifecycle

**Implementation:** `notification-service/src/main/java/org/example/notificationservice/kafka/producer/KafkaProducerService.java:19`

---

### 5. Quota Check (gRPC Client)

**Client:** `TenantServiceGrpcClient`

**Call:**
```java
CheckQuotaRequest request = CheckQuotaRequest.newBuilder()
    .setTenantId(tenantId)
    .build();

CheckQuotaResponse response = tenantServiceStub.checkQuota(request);

if (!response.getIsAllowed()) {
    throw new BaseException(QUOTA_EXCEEDED);  // 429 Too Many Requests
}
```

**Caching:** Results cached in Redis với short TTL (1 minute)
**Cache Key:** `quota:check:{tenantId}`

**⚠️ Note:** This is preliminary check only. Actual quota tracking via Saga pattern trong Tenant Service.

**Implementation:** `notification-service/src/main/java/org/example/notificationservice/service/QuotaCheckService.java`

---

## Performance Characteristics

### Latency Breakdown

**Target: <50ms end-to-end**

```
Request validation:        2-3ms
Quota check (gRPC):        5-10ms (cached: 2ms)
Database save:             10-15ms
Kafka publish:             5-10ms
Response generation:       1-2ms
────────────────────────────────
Total:                     25-40ms ✅
```

### Throughput

- **Capacity:** 2,000+ requests/second per instance
- **Bottleneck:** Database writes (optimizable với batch inserts)
- **Scaling:** Horizontal scaling supported

### Caching Strategy

**Quota Check Cache:**
- **Purpose:** Reduce gRPC calls to Tenant Service
- **TTL:** 60 seconds (short để ensure accuracy)
- **Invalidation:** On quota updates

---

## Error Handling

### Validation Errors (400)

```json
{
  "success": false,
  "error": {
    "code": 2000005,
    "message": "Invalid email format.",
    "details": {"recipient": "not-an-email"}
  }
}
```

**Error Codes:**
- `2000001` - Channel not found
- `2000002` - Channel inactive
- `2000003` - Quota exceeded
- `2000004` - Invalid recipient
- `2000005` - Invalid email format
- `2000006` - Invalid phone format
- `2000007` - Empty content
- `2000008` - Content too long

**Reference:** `notification-service/src/main/java/org/example/notificationservice/common/exception/ApiErrorMessage.java`

### Quota Exceeded (429)

```json
{
  "success": false,
  "error": {
    "code": 2000003,
    "message": "Notification quota exceeded.",
    "details": null
  }
}
```

**Action:** Client should upgrade plan or wait for quota reset.

### Channel Not Found (404)

```json
{
  "success": false,
  "error": {
    "code": 2000001,
    "message": "Channel not found.",
    "details": null
  }
}
```

**Common Cause:** Typo in channel name (must be exact: "EMAIL", "SMS", "TELEGRAM")

---

## Configuration

### Environment Variables

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://notification-db:5432/notification_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# Redis
SPRING_DATA_REDIS_HOST=notification-redis
SPRING_DATA_REDIS_PORT=6379

# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL=http://schema-registry:8081
APP_KAFKA_TOPIC_REQUESTED=notification.requested

# gRPC Client (Tenant Service)
GRPC_CLIENT_TENANT_SERVICE_ADDRESS=static://tenant-service:9090

# OpenTelemetry
OTEL_SERVICE_NAME=notification-service
```

---

## Testing

### Unit Tests

**Test Classes:**
- `NotificationControllerTest` - 6 tests (WebMvcTest)
- `NotificationOrchestrationServiceTest` - 9 tests
- `NotificationValidationServiceTest` - 10 tests
- `QuotaCheckServiceTest` - 7 tests
- `KafkaProducerServiceTest` - 5 tests

**Total:** 37 tests, 100% pass rate

**Run:**
```bash
cd notification-service
mvn test
```

---

## Best Practices

### 1. Always Return 202 ACCEPTED

Notification processing is asynchronous. Never return 200 OK.

### 2. Include Message ID

Clients need message ID to track status:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 3. Validate Before Kafka

Don't publish invalid events to Kafka:
- ✅ Validate channel exists
- ✅ Validate recipient format
- ✅ Check quota

### 4. Transaction Management

Database save + Kafka publish must be transactional:
```java
@Transactional
public SendNotificationResponse send(...) {
    notification = repository.save(notification);
    kafkaProducer.publish(notification);  // Rollback if fails
    return response;
}
```

---

## Related Documentation

- **API Usage:** [API_USAGE.md#sending-notifications](API_USAGE.md#sending-notifications)
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Kafka Topics:** [ARCHITECTURE_DIAGRAMS.md#kafka-topics-flow](diagrams/ARCHITECTURE_DIAGRAMS.md)

---

**Last Updated:** 2025-12-15
**Maintainer:** Notification Hub Team
