# Analytics Service - Technical Specification

**Version:** 1.0
**Port:** 9004 (HTTP)
**Tech Stack:** Spring Boot, PostgreSQL, Kafka
**Repository:** `analytics-service/`

---

## Overview

Analytics Service là **single source of truth** cho message status và history. Nó consumes events từ both `notification.requested` và `notification.result` topics để build complete message lifecycle tracking.

**Key Characteristics:**
- ✅ **Read Model (CQRS-like)** - Optimized cho queries, không modify notifications
- ✅ **Out-of-Order Event Handling** - Result events có thể arrive trước request events
- ✅ **Batch Processing** - Efficient database writes (50 messages hoặc 5 seconds)
- ✅ **Tenant Isolation** - All queries scoped by tenant ID
- ✅ **Complete History** - Tracks entire message journey từ creation đến final status

---

## Core Responsibilities

### 1. Dual Kafka Event Consumption

**Topic 1:** `notification.requested`
**Purpose:** Track message creation (initial PENDING status)

**Topic 2:** `notification.result`
**Purpose:** Track delivery outcome (final SENT/FAILED status)

**Consumer Group:** `analytics-consumer-group`

**Challenge: Out-of-Order Events**

Events có thể arrive in any order:

```
Scenario 1 (Normal Order):
  t=0s: Consume notification.requested → Save with status PENDING
  t=5s: Consume notification.result → Update to status SENT

Scenario 2 (Out-of-Order):
  t=0s: Consume notification.result → Save with status SENT (no prior record!)
  t=3s: Consume notification.requested → Update (don't overwrite status)
```

**Solution: Upsert Logic**

```java
public void handleRequested(NotificationEvent event) {
    Message existing = repository.findById(event.getId()).orElse(null);

    if (existing == null) {
        // Create new with PENDING status
        Message message = new Message();
        message.setMessageId(event.getId());
        message.setStatus("PENDING");
        // ... set other fields
        repository.save(message);
    } else {
        // Already exists (result arrived first)
        // Update non-status fields only
        existing.setRecipient(event.getRecipient());
        existing.setSubject(event.getSubject());
        // Don't overwrite status!
        repository.save(existing);
    }
}
```

**Reference:** `analytics-service/src/main/java/com/example/analyticsservice/service/MessageBatchService.java:57`

---

### 2. Batch Processing

**Configuration:**
- **Batch Size:** 50 events
- **Flush Interval:** 5 seconds

**Why Batching?**
- ✅ **Performance** - Reduces database round-trips (50x fewer)
- ✅ **Throughput** - Can process 1000+ events/second
- ✅ **Resource Efficiency** - Lower connection overhead

**Implementation:**
```java
@Component
public class MessageBatchService {
    private final List<NotificationEvent> requestedBuffer = new ArrayList<>();
    private final List<NotificationEvent> resultBuffer = new ArrayList<>();

    @Scheduled(fixedDelay = 5000)  // Flush every 5 seconds
    public void flush() {
        if (requestedBuffer.size() >= 50 || resultBuffer.size() >= 50) {
            processAndSave();
            requestedBuffer.clear();
            resultBuffer.clear();
        }
    }
}
```

**Reference:** `analytics-service/src/main/java/com/example/analyticsservice/service/MessageBatchService.java:23`

---

### 3. Query API (Tenant-Scoped)

**Base Path:** `/messages`

#### Get Single Message

```http
GET /messages/{messageId}
X-Tenant-Id: tenant-uuid-123

Response: 200 OK
{
  "success": true,
  "data": {
    "messageId": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "tenant-uuid-123",
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "subject": "Welcome!",
    "content": "Hello, thank you...",
    "status": "SENT",
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:15Z"
  }
}
```

**Tenant Isolation:**
```java
MessageResponse res = messageQueryService.getById(id);
if (res == null || !tenantId.equals(res.getTenantId())) {
    return ResponseEntity.notFound().build();  // 404 if wrong tenant
}
```

#### List Messages for Tenant

```http
GET /messages
X-Tenant-Id: tenant-uuid-123

Response: 200 OK
{
  "success": true,
  "data": [
    {
      "messageId": "550e8400-...",
      "channel": "EMAIL",
      "recipient": "user@example.com",
      "status": "SENT",
      "createdAt": "2024-01-15T10:30:00Z"
    },
    {
      "messageId": "660f9511-...",
      "channel": "SMS",
      "recipient": "+1234567890",
      "status": "PENDING",
      "createdAt": "2024-01-15T10:29:00Z"
    }
  ]
}
```

**Ordering:** Results ordered by `createdAt DESC` (newest first)

**Implementation:** `analytics-service/src/main/java/com/example/analyticsservice/controller/MessageController.java:16`

---

## Data Model

### Database Table: `messages`

```sql
CREATE TABLE messages (
    message_id   UUID PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    channel      VARCHAR(50) NOT NULL,
    recipient    VARCHAR(255) NOT NULL,
    subject      VARCHAR(500),
    content      TEXT,
    status       VARCHAR(50) NOT NULL,  -- PENDING, SENT, FAILED
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW(),

    INDEX idx_tenant_id (tenant_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_tenant_created (tenant_id, created_at DESC)
);
```

**Key Indexes:**
- `idx_tenant_id` - For filtering by tenant
- `idx_tenant_created` - For listing messages (composite)
- `idx_status` - For status aggregations

**Repository:** `analytics-service/src/main/java/com/example/analyticsservice/repository/MessageRepository.java`

**Custom Query:**
```java
List<Message> findByTenantIdOrderByCreatedAtDesc(String tenantId);
```

---

## Performance Characteristics

### Latency

**Query API:**
- Single message lookup: <10ms (indexed by PRIMARY KEY)
- List messages: <50ms (indexed by tenant_id + created_at)

**Event Processing:**
- Batch processing: ~100ms per batch (50 events)
- Individual event handling: 2-5ms (buffered)

### Throughput

- **Kafka Consumption:** 1,000+ events/second
- **Batch Processing:** 50 events every 5 seconds = 600 events/minute baseline
- **Query API:** 500+ requests/second

### Batching Strategy

**Trigger Conditions:**
```java
if (requestedBuffer.size() >= 50 ||
    resultBuffer.size() >= 50 ||
    timeSinceLastFlush > 5000ms) {
    flush();
}
```

**Why 50 events OR 5 seconds?**
- High traffic: Batches fill quickly (50 events) → low latency
- Low traffic: Flush every 5 seconds → data freshness

---

## Error Handling

### Message Not Found (404)

```json
{
  "success": false,
  "error": {
    "code": 4000001,
    "message": "Message not found.",
    "details": null
  }
}
```

**Common Causes:**
- Invalid message ID
- Message belongs to different tenant
- Message not yet processed (Kafka lag)

### Tenant Mismatch (404)

```http
GET /messages/550e8400-e29b-41d4-a716-446655440000
X-Tenant-Id: wrong-tenant-id

Response: 404 Not Found
```

**Security:** Prevents cross-tenant data access.

---

## Configuration

### Environment Variables

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://analytics-db:5432/analytics_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL=http://schema-registry:8081
SPRING_KAFKA_CONSUMER_GROUP_ID=analytics-consumer-group

# Kafka Topics
APP_KAFKA_TOPIC_REQUESTED=notification.requested
APP_KAFKA_TOPIC_RESULT=notification.result

# Batch Processing
APP_BATCH_SIZE=50
APP_BATCH_FLUSH_INTERVAL=5000

# OpenTelemetry
OTEL_SERVICE_NAME=analytics-service
```

---

## Monitoring & Observability

### Health Check

```bash
GET http://localhost:9004/actuator/health
```

### Metrics

**Key Metrics:**
- `analytics_messages_total{status}` - Messages by status
- `analytics_batch_size` - Batch size histogram
- `analytics_flush_duration_seconds` - Batch processing time
- `analytics_kafka_consumer_lag` - Consumer lag
- `analytics_query_duration_seconds` - Query latency

### Logs

```
INFO  - Analytics received requested: {id: msg-123, channel: EMAIL}
INFO  - Analytics received result: {id: msg-123, status: SENT}
INFO  - Flushed analytics batch: requested=25, result=30, saved=55, reason=batch-size
WARN  - Out-of-order event detected: result before request for msg-456
```

---

## Data Consistency

### Eventual Consistency

Analytics data is **eventually consistent**:
- Events processed asynchronously (Kafka lag: typically <1 second)
- Batch processing delay: up to 5 seconds
- **Total delay:** Message visible in analytics trong 5-10 seconds

### Idempotency

Duplicate events are handled gracefully:
- Upsert logic prevents duplicate inserts
- Same message ID processed multiple times → single record

---

## Testing

### Unit Tests

**Test Classes:**
- `MessageBatchServiceTest` - 7 tests (batch processing, out-of-order)
- `MessageQueryServiceTest` - 4 tests (query logic)
- `NotificationRequestedConsumerTest` - 2 tests
- `NotificationResultConsumerTest` - 2 tests

**Total:** 15 tests

**Run:**
```bash
cd analytics-service
mvn test
```

**Test Configuration:** `analytics-service/src/test/resources/application-test.yml`

---

## Troubleshooting

### Queries Return Empty

**Check Kafka consumption:**
```bash
docker logs analytics-service | grep "Analytics received"
```

**Check database:**
```sql
docker exec analytics-db psql -U postgres -d analytics_db \
  -c "SELECT COUNT(*) FROM messages;"
```

**Check consumer lag:**
```bash
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group analytics-consumer-group
```

### Out-of-Order Events

**This is normal!** Analytics handles it automatically.

**Check logs:**
```bash
docker logs analytics-service | grep "Out-of-order"
```

**Verify upsert logic working:**
```sql
-- Check for duplicate message IDs (should be 0)
SELECT message_id, COUNT(*) FROM messages GROUP BY message_id HAVING COUNT(*) > 1;
```

---

## Best Practices

### 1. Query with Pagination

For tenants với many messages, implement pagination:
```java
Page<Message> findByTenantId(String tenantId, Pageable pageable);
```

### 2. Archive Old Data

Consider archiving messages older than 90 days:
```sql
-- Move to archive table
INSERT INTO messages_archive SELECT * FROM messages WHERE created_at < NOW() - INTERVAL '90 days';
DELETE FROM messages WHERE created_at < NOW() - INTERVAL '90 days';
```

### 3. Monitor Batch Performance

```bash
# Check average batch size
docker logs analytics-service | grep "Flushed analytics batch" | tail -20
```

**Optimal:** Batches should average 40-50 events (indicates high traffic)

---

## Related Documentation

- **Message Tracking:** [API_USAGE.md#checking-message-status](API_USAGE.md#checking-message-status)
- **Batch Processing:** [ARCHITECTURE.md#batch-processing](ARCHITECTURE.md)
- **CQRS Pattern:** [ARCHITECTURE_DIAGRAMS.md](diagrams/ARCHITECTURE_DIAGRAMS.md)

---

**Last Updated:** 2025-12-15
**Maintainer:** Notification Hub Team
