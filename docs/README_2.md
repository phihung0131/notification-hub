# Notification Hub

**Multi-Tenant SaaS Notification Platform** với event-driven microservices architecture.

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.0-green)]()
[![Kafka](https://img.shields.io/badge/Kafka-KRaft-black)]()
[![License](https://img.shields.io/badge/license-MIT-blue)]()

---

## 🎯 Overview

Notification Hub enables tenants to send multi-channel notifications (Email, SMS, Telegram) through a unified asynchronous API with comprehensive features:

- ✅ **Multi-Channel Support** - Email, SMS, Telegram (extensible)
- ✅ **Quota Management** - Per-tenant limits với Saga pattern
- ✅ **Real-Time Tracking** - Complete message lifecycle monitoring
- ✅ **Multi-Tenancy** - Secure tenant isolation
- ✅ **High Performance** - <50ms API response time, async processing
- ✅ **Event-Driven** - Kafka-based reliable message delivery
- ✅ **Observability** - OpenTelemetry distributed tracing

---

## 🏗️ Architecture

```
┌─────────────┐      ┌──────────────┐      ┌────────────────────┐
│   Client    │─────▶│   Gateway    │─────▶│   Notification     │
│             │      │   Service    │      │   Service          │
└─────────────┘      └──────────────┘      └────────────────────┘
                            │                        │
                            │ validates              │ publishes
                            ▼                        ▼
                     ┌──────────────┐         ┌──────────────┐
                     │    Tenant    │         │    Kafka     │
                     │   Service    │         │  (KRaft)     │
                     └──────────────┘         └──────────────┘
                            │                        │
                            │ quota saga             │ consumes
                            ▼                        ▼
                     ┌──────────────┐         ┌──────────────┐
                     │  Analytics   │◀────────│   Delivery   │
                     │   Service    │         │   Service    │
                     └──────────────┘         └──────────────┘
```

### Core Services ("Big Four")

1. **Gateway Service** (port 9000)
   - API Gateway với API key authentication
   - Rate limiting (Redis-based)
   - Routes requests và injects tenant context

2. **Tenant Service** (HTTP: 9001, gRPC: 5001)
   - Authentication (JWT) và authorization
   - Multi-tenant management
   - Quota tracking với Saga pattern
   - gRPC endpoints cho Gateway & Notification

3. **Notification Service** (HTTP: 9002)
   - Fast API entry point (<50ms response)
   - Validation, quota check, Kafka publish
   - Returns 202 ACCEPTED với message ID

4. **Delivery Service** (HTTP: 9003)
   - Background delivery workers
   - Strategy pattern với channel adapters
   - Retry logic (max 3 attempts) + DLQ

5. **Analytics Service** (HTTP: 9004)
   - Single source of truth cho message status
   - Consumes notification.requested & notification.result
   - Query API cho message tracking

---

## 🚀 Quick Start (5 Minutes)

### Prerequisites

- **Java 21+** (JDK 21 recommended)
- **Docker & Docker Compose** (for infrastructure)
- **Maven 3.8+**
- **8GB RAM** minimum

### Step 1: Clone Repository

```bash
git clone https://github.com/your-org/notification-hub.git
cd notification-hub
```

### Step 2: Build All Services

```bash
# Build commons-shared first (includes proto & avro generation)
mvn clean install -pl commons-shared

# Build all services
mvn clean install -Ptenant,notification,delivery,analytics,gateway
```

### Step 3: Start Infrastructure

```bash
# Create Docker network
docker network create notification-net

# Start Kafka, Schema Registry, PostgreSQL, Redis
docker compose -f docker/docker-compose.kafka-dev.yml up -d
```

### Step 4: Start Services

```bash
# Start each service in separate terminals (with hot reload)
docker compose -f docker/docker-compose.gateway-dev.yml up --watch
docker compose -f docker/docker-compose.tenant-dev.yml up --watch
docker compose -f docker/docker-compose.notification-dev.yml up --watch
docker compose -f docker/docker-compose.delivery-dev.yml up --watch
docker compose -f docker/docker-compose.analytics-dev.yml up --watch
```

### Step 5: Send Your First Notification

```bash
# 1. Register tenant
curl -X POST http://localhost:9000/api/v1/tenants/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Tenant",
    "email": "test@example.com",
    "password": "password123"
  }'

# 2. Login (get JWT token)
curl -X POST http://localhost:9000/api/v1/tenants/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'

# 3. Get API key (use JWT from step 2)
curl -X GET http://localhost:9000/api/v1/tenants/apikeys \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 4. Send notification
curl -X POST http://localhost:9000/api/v1/notifications/send \
  -H "Authorization: Bearer <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "subject": "Welcome!",
    "content": "Hello from Notification Hub!"
  }'

# 5. Check status (use message ID from step 4)
curl -X GET http://localhost:9000/api/v1/analytics/messages/<MESSAGE_ID> \
  -H "Authorization: Bearer <API_KEY>"
```

**🎉 Done!** Your notification is being processed asynchronously.

---

## 💻 Technology Stack

| Layer | Technology |
|-------|------------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.4.0 |
| **Messaging** | Apache Kafka (KRaft mode) |
| **Databases** | PostgreSQL 16 |
| **Caching** | Redis 7 |
| **RPC** | gRPC with Protocol Buffers |
| **Serialization** | Avro with Confluent Schema Registry |
| **Observability** | OpenTelemetry, Jaeger |
| **Container** | Docker, Docker Compose |

---

## 📚 Documentation

### Getting Started
- [Development Setup](docs/DEVELOPMENT.md) - Complete local development guide
- [API Usage Examples](API_USAGE.md) - cURL & Postman collections
- [Troubleshooting](TROUBLESHOOTING.md) - Common issues & solutions

### Architecture
- [Architecture Overview](ARCHITECTURE.md) - System design & patterns
- [Service Specifications](docs/) - Individual service specs
- [Data Flow](ARCHITECTURE.md#data-flow) - Request lifecycle

### Operations
- [Environment Configuration](docs/CONFIGURATION.md) - All environment variables
- [Monitoring & Observability](docs/DEVELOPMENT.md#monitoring) - Metrics, traces, logs

### Development
- [Contributing Guide](../CLAUDE.md) - For AI-assisted development
- [Adding Delivery Channels](docs/DEVELOPMENT.md#adding-channels) - Extend with new channels

---

## 🔑 Key Features

### 1. Multi-Channel Delivery
Support cho multiple notification channels với Strategy pattern:
- **Email** - SMTP delivery (mock implementation included)
- **SMS** - SMS gateway integration (mock)
- **Telegram** - Bot API integration (mock)
- **Extensible** - Add new channels without modifying core code

### 2. Quota Management with Saga Pattern
- **Preliminary Check** - Fast quota validation before accepting requests
- **Saga Pattern** - Eventual consistency, quota incremented ONLY on successful delivery
- **Per-Tenant Limits** - Configurable quotas (default: 1000/month, unlimited: -1)

### 3. Real-Time Message Tracking
- **Single Source of Truth** - Analytics Service tracks complete lifecycle
- **Status Query** - REST API to check delivery status
- **Out-of-Order Handling** - Handles events arriving in any order

### 4. High Performance
- **Fast API** - <50ms response time for notification requests
- **Async Processing** - Immediate 202 ACCEPTED response
- **Batch Processing** - Efficient Kafka consumption (50 events or 5s intervals)
- **Redis Caching** - API key validation, quota checks

---

## 📖 API Examples

### Send Email Notification

```bash
POST /api/v1/notifications/send
Authorization: Bearer sk_live_abc123...
Content-Type: application/json

{
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "subject": "Account Verification",
  "content": "Click here to verify: https://example.com/verify?token=xyz"
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

### Check Message Status

```bash
GET /api/v1/analytics/messages/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer sk_live_abc123...
```

**Response:**
```json
{
  "success": true,
  "data": {
    "messageId": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "tenant-uuid-123",
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "status": "SENT",
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:15Z"
  }
}
```

More examples: [API_USAGE.md](API_USAGE.md)

---

## 🛠️ Development

### Running Tests

```bash
# All tests
mvn test

# Specific service
cd tenant-service && mvn test

# Specific test class
mvn test -Dtest=AuthServiceTest

# With coverage report (JaCoCo)
mvn clean test jacoco:report
```

### Watch Mode (Hot Reload)

```bash
# Services auto-restart on code changes
docker compose -f docker/docker-compose.notification-dev.yml up --watch
```

### Accessing Services

| Service | HTTP Port | gRPC Port | Debug Port |
|---------|-----------|-----------|------------|
| Gateway | 9000 | - | - |
| Tenant | 9001 | 50001 | 5001 |
| Notification | 9002 | 50002 | 5002 |
| Delivery | 9003 | - | 5003 |
| Analytics | 9004 | - | 5004 |

**Infrastructure:**
- Kafka UI: http://localhost:8080
- Jaeger Tracing: http://localhost:16686 (if monitoring enabled)

---

## 🏛️ Design Principles

### SOLID Principles
- ✅ **Single Responsibility** - Each service focuses on one domain
- ✅ **Open/Closed** - Add channels via adapters, no core changes
- ✅ **Liskov Substitution** - All adapters interchangeable
- ✅ **Interface Segregation** - Clean, focused gRPC interfaces
- ✅ **Dependency Inversion** - Depend on abstractions (DeliveryAdapter, etc.)

### Architectural Patterns
- **Saga Pattern** - Quota management với eventual consistency
- **Strategy Pattern** - Channel adapters (DeliveryAdapterRegistry)
- **Facade Pattern** - NotificationOrchestrationService
- **CQRS-like** - Write (Notification) separated từ Read (Analytics)
- **Cache-Aside** - Redis caching cho API keys & quotas

---

## 🧪 Testing

**Test Coverage:**
- **Unit Tests:** 90+ tests covering services, controllers, adapters
- **Integration Tests:** gRPC, Kafka, Database
- **Test Strategy:** AAA pattern, mocking với Mockito, Testcontainers

**Run Coverage Report:**
```bash
mvn clean test jacoco:report
# Open: target/site/jacoco/index.html
```

---

## 📦 Project Structure

```
notification-hub/
├── commons-shared/          # Shared utilities, base classes, gRPC protos & Avro schemas
├── gateway-service/         # API Gateway (port 9000)
├── tenant-service/          # Auth & quota (HTTP: 9001, gRPC: 50001)
├── notification-service/    # Notification orchestration (port 9002)
├── delivery-service/        # Background delivery workers (port 9003)
├── analytics-service/       # Message tracking (port 9004)
├── docker/                  # Docker Compose configurations
├── docs/                    # Comprehensive documentation
└── examples/                # API usage examples (Postman, cURL)
```

---

## 🔐 Security

- **API Key Authentication** - Gateway validates all requests
- **JWT Tokens** - For tenant management operations
- **Tenant Isolation** - Enforced at database & application level
- **Password Encryption** - BCrypt hashing
- **Rate Limiting** - Redis-based (50 req/sec replenish, 100 burst)

xin chafsss chaf