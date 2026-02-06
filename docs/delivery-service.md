# Delivery Service - Technical Specification

**Version:** 1.0
**Port:** 9003 (HTTP)
**Tech Stack:** Spring Boot, Kafka, Strategy Pattern
**Repository:** `delivery-service/`

---

## Overview

Delivery Service là **background worker** chịu trách nhiệm actual delivery notifications qua various channels. Nó consumes từ Kafka, attempts delivery với retry logic, và publishes results.

**Key Characteristics:**
- ✅ **Strategy Pattern** - Pluggable channel adapters
- ✅ **Retry Logic** - Max 3 attempts với exponential backoff
- ✅ **DLQ Support** - Failed messages sent to Dead Letter Queue
- ✅ **Stateless** - No database, pure event processor
- ✅ **Scalable** - Multiple instances can run in parallel (Kafka consumer group)

---

## Core Responsibilities

### 1. Kafka Event Consumption

**Topic:** `notification.requested`
**Consumer Group:** `delivery-consumer-group`
**Concurrency:** Configurable (default: 3 threads)

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

**Consumer Implementation:** `delivery-service/src/main/java/com/example/deliveryservice/kafka/consumer/NotificationRequestedConsumer.java`

**Processing:**
1. Deserialize Avro message
2. Delegate to `DeliveryProcessor`
3. Commit offset only after successful processing

---

### 2. Channel Adapter Selection (Strategy Pattern)

**Registry:** `DeliveryAdapterRegistry`

**How It Works:**
```java
// Auto-discovery via Spring component scanning
@Component
public class DeliveryAdapterRegistry {
    private Map<String, DeliveryAdapter> adapters;

    @Autowired
    public DeliveryAdapterRegistry(List<DeliveryAdapter> adapterList) {
        adapters = adapterList.stream()
            .collect(Collectors.toMap(
                DeliveryAdapter::getChannel,
                adapter -> adapter
            ));
    }

    public DeliveryAdapter getAdapter(String channel) {
        return adapters.getOrDefault(
            channel.toLowerCase(),
            defaultAdapter
        );
    }
}
```

**Available Adapters:**

| Adapter | Channel Code | Implementation | Production Ready |
|---------|--------------|----------------|------------------|
| MockEmailAdapter | `email` | Mock simulation | ❌ (replace với SMTP) |
| MockSmsAdapter | `sms` | Mock simulation | ❌ (replace với Twilio) |
| MockTelegramAdapter | `telegram` | Mock simulation | ❌ (replace với Bot API) |
| MockDefaultAdapter | `default` | Fallback | ❌ |

**Reference:** `delivery-service/src/main/java/com/example/deliveryservice/service/DeliveryAdapterRegistry.java:22`

---

### 3. Delivery Processing với Retry Logic

**Processor:** `DeliveryProcessor`

**Algorithm:**
```java
int maxRetries = 3;
long initialBackoffMs = 500;

for (int attempt = 1; attempt <= maxRetries; attempt++) {
    try {
        DeliveryAdapter adapter = registry.getAdapter(event.getChannel());
        DeliveryResult result = adapter.deliver(event);

        if (result.isSuccess()) {
            publishResult(event, "SENT");
            return;  // Success!
        }
    } catch (Exception e) {
        if (attempt == maxRetries) {
            publishResult(event, "FAILED");
            publishToDLQ(event, e);
            return;  // Max retries exhausted
        }

        long backoff = initialBackoffMs * (long) Math.pow(2, attempt - 1);
        Thread.sleep(backoff);  // Exponential backoff: 500ms, 1000ms, 2000ms
    }
}
```

**Backoff Strategy:**
- Attempt 1: No delay
- Attempt 2: 500ms delay
- Attempt 3: 1000ms delay
- After 3: Send to DLQ

**Implementation:** `delivery-service/src/main/java/com/example/deliveryservice/service/DeliveryProcessor.java:93`

---

### 4. Result Publishing

**Success Case:**

**Topic:** `notification.result`
**Payload:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "tenant-uuid-123",
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "status": "SENT",           // ← Success status
  "createdAt": 1705319400000
}
```

**Consumers:**
- Tenant Service: Increments quota
- Analytics Service: Updates message status

---

**Failure Case:**

**Topic 1:** `notification.result`
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "FAILED",         // ← Failure status
  ...
}
```

**Topic 2:** `notification.dlq` (Dead Letter Queue)
```json
{
  "originalEvent": { ... },
  "error": "SMTP connection timeout after 3 retries",
  "attempts": 3,
  "failedAt": 1705319500000
}
```

**Implementation:** `delivery-service/src/main/java/com/example/deliveryservice/kafka/producer/KafkaProducerService.java`

---

## Adapter Interface

### DeliveryAdapter Contract

```java
public interface DeliveryAdapter {
    /**
     * Delivers notification via channel-specific mechanism.
     * @throws RuntimeException if delivery fails (triggers retry)
     */
    DeliveryResult deliver(NotificationEvent event);

    /**
     * Channel code this adapter handles (must match Channel.code in DB)
     */
    String getChannel();
}
```

**Reference:** `delivery-service/src/main/java/com/example/deliveryservice/service/adapter/DeliveryAdapter.java:60`

---

### Adding New Channel

**Example: Implementing Real SMTP Adapter**

```java
@Component
public class SmtpEmailAdapter implements DeliveryAdapter {

    private final JavaMailSender mailSender;

    @Autowired
    public SmtpEmailAdapter(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public DeliveryResult deliver(NotificationEvent event) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setTo(event.getRecipient().toString());
            helper.setSubject(event.getSubject().toString());
            helper.setText(event.getContent().toString(), true);

            mailSender.send(message);

            return DeliveryResult.builder()
                .success(true)
                .messageId(event.getId().toString())
                .details("Email sent successfully via SMTP")
                .build();

        } catch (Exception e) {
            throw new RuntimeException("SMTP delivery failed", e);
        }
    }

    @Override
    public String getChannel() {
        return "email";  // Must match Channel.code in database
    }
}
```

**Steps:**
1. Create new class implementing `DeliveryAdapter`
2. Add `@Component` annotation
3. Implement `deliver()` và `getChannel()` methods
4. Registry auto-discovers và registers adapter
5. No changes to existing code needed! (Open/Closed Principle)

---

## Configuration

### Environment Variables

```bash
# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL=http://schema-registry:8081
SPRING_KAFKA_CONSUMER_GROUP_ID=delivery-consumer-group

# Kafka Topics
APP_KAFKA_TOPIC_REQUESTED=notification.requested
APP_KAFKA_TOPIC_RESULT=notification.result
APP_KAFKA_TOPIC_DLQ=notification.dlq

# Retry Configuration
DELIVERY_MAX_RETRIES=3
DELIVERY_INITIAL_BACKOFF_MS=500
DELIVERY_MAX_BACKOFF_MS=5000

# OpenTelemetry
OTEL_SERVICE_NAME=delivery-service
```

### application.yml

```yaml
app:
  kafka:
    topic:
      requested: notification.requested
      result: notification.result
      dlq: notification.dlq

delivery:
  max-retries: 3
  initial-backoff-ms: 500
  max-backoff-ms: 5000

spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}
    consumer:
      group-id: delivery-consumer-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      properties:
        specific.avro.reader: true
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    properties:
      schema.registry.url: ${SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL}
```

---

## Mock Adapters (Development)

### BaseMockAdapter

**Simulated Behavior:**
- **Success Rate:** 90% (random)
- **Latency:** 100-500ms (random sleep)
- **Logging:** Logs delivery attempt

**Implementation:**
```java
public abstract class BaseMockAdapter implements DeliveryAdapter {
    @Override
    public DeliveryResult deliver(NotificationEvent event) {
        simulateDelivery();  // Random sleep 100-500ms

        if (Math.random() < 0.9) {  // 90% success rate
            return DeliveryResult.success(event.getId().toString());
        } else {
            throw new RuntimeException("Simulated delivery failure");
        }
    }
}
```

**Reference:** `delivery-service/src/main/java/com/example/deliveryservice/service/adapter/BaseMockAdapter.java`

---

## Monitoring & Observability

### Metrics

**Key Metrics:**
- `delivery_attempts_total{channel, status}` - Total delivery attempts
- `delivery_success_rate{channel}` - Success rate by channel
- `delivery_retry_count` - Retry histogram
- `delivery_duration_seconds{channel}` - Delivery latency
- `delivery_dlq_messages_total` - Messages sent to DLQ

### Logs

**Important Events:**
```
INFO  - Processing delivery for message: msg-123, channel: EMAIL
INFO  - Delivery attempt 1/3 succeeded for message: msg-123
WARN  - Delivery attempt 1/3 failed for message: msg-456, retrying...
ERROR - Delivery failed after 3 attempts, sending to DLQ: msg-789
INFO  - Publishing result to notification.result: {status: SENT}
```

### Distributed Tracing

**Spans:**
- `delivery.consume` - Kafka message consumption
- `delivery.process` - Delivery processing
- `delivery.adapter.{channel}` - Channel-specific delivery
- `delivery.publish_result` - Result publishing

---

## Troubleshooting

### Messages Stuck in PENDING

**Check consumer is running:**
```bash
docker logs delivery-service | grep "Processing delivery"
```

**Check Kafka lag:**
```bash
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group delivery-consumer-group
```

### High Failure Rate

**Check adapter logs:**
```bash
docker logs delivery-service | grep "Delivery attempt"
```

**Check DLQ:**
```bash
# Consume from DLQ to see failed messages
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic notification.dlq \
  --from-beginning
```

### Adapter Not Found

**Check registry initialization:**
```bash
docker logs delivery-service | grep "Registered adapter"

# Expected output:
# Registered adapter: email -> MockEmailAdapter
# Registered adapter: sms -> MockSmsAdapter
# Registered adapter: telegram -> MockTelegramAdapter
# Registered adapter: default -> MockDefaultAdapter
```

---

## Production Readiness

### Replace Mock Adapters

**Email (SMTP):**
```bash
# Add dependency
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

**SMS (Twilio):**
```bash
<dependency>
    <groupId>com.twilio.sdk</groupId>
    <artifactId>twilio</artifactId>
    <version>9.14.1</version>
</dependency>
```

**Telegram:**
```bash
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots</artifactId>
    <version>6.8.0</version>
</dependency>
```

### Configuration for Production

```yaml
# SMTP Example
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

# Twilio Example
twilio:
  account-sid: ${TWILIO_ACCOUNT_SID}
  auth-token: ${TWILIO_AUTH_TOKEN}
  from-number: ${TWILIO_FROM_NUMBER}

# Telegram Example
telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
```

---

## Testing

### Unit Tests

**Test Classes:**
- `DeliveryProcessorTest` - 7 tests (retry logic, DLQ)
- `DeliveryAdapterRegistryTest` - 4 tests (adapter discovery)

**Run:**
```bash
cd delivery-service
mvn test
```

---

## Best Practices

### 1. Idempotent Delivery

Adapters should handle duplicate events:
```java
// Check if already delivered (store message IDs in cache)
if (deliveryCache.contains(event.getId())) {
    return DeliveryResult.success(event.getId());  // Already sent
}
```

### 2. Timeout Configuration

Set reasonable timeouts for external APIs:
```java
RestTemplate restTemplate = new RestTemplateBuilder()
    .setConnectTimeout(Duration.ofSeconds(5))
    .setReadTimeout(Duration.ofSeconds(30))
    .build();
```

### 3. Monitor DLQ

Set up alerts for DLQ messages:
```bash
# Alert if DLQ has messages
kafka-consumer-groups --describe --group dlq-monitor
```

---

## Related Documentation

- **Adding Channels:** [DEVELOPMENT.md#adding-delivery-channels](DEVELOPMENT.md)
- **Strategy Pattern:** [ARCHITECTURE_DIAGRAMS.md#delivery-service-strategy-pattern](diagrams/ARCHITECTURE_DIAGRAMS.md)
- **Kafka Configuration:** [ARCHITECTURE.md#kafka-configuration](ARCHITECTURE.md)

---

**Last Updated:** 2025-12-15
**Maintainer:** Notification Hub Team
