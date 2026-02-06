# Notification Hub - Architecture Diagrams

Comprehensive visual documentation of system architecture.

---

## System Architecture Overview

```mermaid
graph TB
    Client[Client Application]

    subgraph "API Gateway Layer"
        Gateway[Gateway Service<br/>Port: 9000<br/>API Key Auth + Rate Limiting]
    end

    subgraph "Core Services"
        Tenant[Tenant Service<br/>HTTP: 9001, gRPC: 50001<br/>Auth, Quota, Multi-Tenancy]
        Notification[Notification Service<br/>HTTP: 9002<br/>Fast API Entry Point]
        Delivery[Delivery Service<br/>HTTP: 9003<br/>Background Workers]
        Analytics[Analytics Service<br/>HTTP: 9004<br/>Message Tracking]
    end

    subgraph "Message Broker"
        Kafka[Apache Kafka KRaft<br/>notification.requested<br/>notification.result<br/>notification.dlq]
    end

    subgraph "Data Layer"
        TenantDB[(PostgreSQL<br/>tenant_db)]
        NotificationDB[(PostgreSQL<br/>notification_db)]
        AnalyticsDB[(PostgreSQL<br/>analytics_db)]
        Redis[(Redis<br/>Cache + Rate Limit)]
    end

    subgraph "External Services Mock"
        SMTP[SMTP Server]
        SMS[SMS Gateway]
        TelegramAPI[Telegram Bot API]
    end

    Client -->|1. API Request| Gateway
    Gateway -->|2. Validate API Key| Tenant
    Gateway -->|3. Inject X-Tenant-Id| Notification

    Notification -->|4a. Check Quota gRPC| Tenant
    Notification -->|4b. Save PENDING| NotificationDB
    Notification -->|4c. Publish| Kafka

    Kafka -->|5a. Consume| Delivery
    Kafka -->|5b. Track| Analytics

    Delivery -->|6. Deliver via Channel| SMTP
    Delivery -->|6. Deliver via Channel| SMS
    Delivery -->|6. Deliver via Channel| TelegramAPI
    Delivery -->|7. Publish Result| Kafka

    Kafka -->|8a. Increment Quota| Tenant
    Kafka -->|8b. Update Status| Analytics

    Tenant -.->|Cache| Redis
    Gateway -.->|Rate Limit| Redis
    Tenant -.->|Read/Write| TenantDB
    Analytics -.->|Read/Write| AnalyticsDB

    style Client fill:#e1f5ff
    style Gateway fill:#fff3cd
    style Kafka fill:#d1ecf1
    style Redis fill:#f8d7da
```

---

## Data Flow Sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant GW as Gateway
    participant T as Tenant Service
    participant N as Notification Service
    participant K as Kafka
    participant D as Delivery Service
    participant A as Analytics Service

    C->>GW: POST /send (Authorization: Bearer sk_live_...)
    GW->>T: Validate API Key (REST)
    T-->>GW: Valid (tenantId, permissions)
    GW->>N: Forward Request (X-Tenant-Id injected)

    N->>T: Check Quota (gRPC)
    T-->>N: Has Quota (remaining: 500)

    N->>N: Save to DB (status: PENDING)
    N->>K: Publish to notification.requested
    N-->>C: 202 ACCEPTED (messageId)

    Note over C,N: Client receives response in <50ms

    K->>D: Consume notification.requested
    K->>A: Consume notification.requested

    A->>A: Save Message (status: PENDING)

    D->>D: Select Adapter (Strategy Pattern)
    D->>D: Attempt Delivery (max 3 retries)
    alt Delivery Success
        D->>K: Publish to notification.result (status: SENT)
    else Delivery Failed
        D->>K: Publish to notification.dlq (after 3 retries)
        D->>K: Publish to notification.result (status: FAILED)
    end

    K->>T: Consume notification.result
    K->>A: Consume notification.result

    alt Status = SENT
        T->>T: Increment quotaUsed (Saga Pattern)
    else Status = FAILED
        T->>T: No quota increment
    end

    A->>A: Update Message Status

    C->>GW: GET /analytics/messages/{messageId}
    GW->>A: Forward Request
    A-->>C: Message Details (status: SENT/FAILED)
```

---

## Quota Saga Pattern

```mermaid
stateDiagram-v2
    [*] --> QuotaCheck: Client sends notification

    QuotaCheck --> SavePending: Quota available
    QuotaCheck --> Rejected: Quota exceeded

    SavePending --> PublishKafka: Save to DB
    PublishKafka --> DeliveryAttempt: notification.requested

    DeliveryAttempt --> Retry: Attempt 1 fails
    Retry --> Retry: Attempt 2 fails
    Retry --> DLQ: Attempt 3 fails (max retries)
    DeliveryAttempt --> PublishResult: Success on any attempt

    DLQ --> PublishFailed: Send to notification.dlq
    PublishFailed --> NoQuotaIncrement: status: FAILED

    PublishResult --> IncrementQuota: status: SENT
    IncrementQuota --> [*]: Quota updated
    NoQuotaIncrement --> [*]: Quota unchanged

    Rejected --> [*]: 429 Too Many Requests

    note right of IncrementQuota
        Saga Pattern:
        Quota ONLY incremented
        when status = SENT
        (eventual consistency)
    end note
```

---

## Delivery Service - Strategy Pattern

```mermaid
classDiagram
    class DeliveryAdapter {
        <<interface>>
        +deliver(event) DeliveryResult
        +getChannel() String
    }

    class BaseMockAdapter {
        <<abstract>>
        +deliver(event) DeliveryResult
        #simulateDelivery()
    }

    class MockEmailAdapter {
        +getChannel() "email"
    }

    class MockSmsAdapter {
        +getChannel() "sms"
    }

    class MockTelegramAdapter {
        +getChannel() "telegram"
    }

    class MockDefaultAdapter {
        +getChannel() "default"
    }

    class DeliveryAdapterRegistry {
        -Map~String,DeliveryAdapter~ adapters
        +getAdapter(channel) DeliveryAdapter
        +registerAdapter(adapter)
    }

    class DeliveryProcessor {
        -DeliveryAdapterRegistry registry
        +processDelivery(event)
        -retryWithBackoff()
    }

    DeliveryAdapter <|.. BaseMockAdapter
    BaseMockAdapter <|-- MockEmailAdapter
    BaseMockAdapter <|-- MockSmsAdapter
    BaseMockAdapter <|-- MockTelegramAdapter
    BaseMockAdapter <|-- MockDefaultAdapter

    DeliveryAdapterRegistry o-- DeliveryAdapter
    DeliveryProcessor --> DeliveryAdapterRegistry

    note for DeliveryAdapter "Strategy Pattern:\nAdd new channels\nwithout modifying\nexisting code"
```

---

## Deployment Architecture

```mermaid
graph TB
    subgraph "Docker Network: notification-net"
        subgraph "Gateway Tier"
            GW[gateway-service:9000]
        end

        subgraph "Service Tier"
            TS[tenant-service:9001<br/>gRPC:50001<br/>Debug:5001]
            NS[notification-service:9002<br/>gRPC:50002<br/>Debug:5002]
            DS[delivery-service:9003<br/>Debug:5003]
            AS[analytics-service:9004<br/>Debug:5004]
        end

        subgraph "Message Broker"
            K1[Kafka Broker<br/>9092]
            SR[Schema Registry<br/>8081]
            KUI[Kafka UI<br/>8080]
        end

        subgraph "Data Tier"
            TDB[(tenant-db:5432)]
            NDB[(notification-db:5433)]
            ADB[(analytics-db:5435)]
            R[Redis:6379]
        end

        subgraph "Observability Optional"
            J[Jaeger:16686]
            OTEL[OTEL Collector:4317]
        end
    end

    GW --> TS
    GW --> NS
    GW --> AS

    NS --> K1
    DS --> K1
    TS --> K1
    AS --> K1
    K1 --> SR

    TS --> TDB
    NS --> NDB
    AS --> ADB

    TS --> R
    GW --> R

    TS -.->|Traces| OTEL
    NS -.->|Traces| OTEL
    DS -.->|Traces| OTEL
    AS -.->|Traces| OTEL
    OTEL -.-> J

    style GW fill:#fff3cd
    style K1 fill:#d1ecf1
    style SR fill:#d1ecf1
    style R fill:#f8d7da
```

---

## Kafka Topics Flow

```mermaid
graph LR
    NS[Notification<br/>Service]
    DS[Delivery<br/>Service]
    TS[Tenant<br/>Service]
    AS[Analytics<br/>Service]

    subgraph "Kafka Topics"
        REQ[notification.requested<br/>Key: messageId<br/>Schema: NotificationEvent]
        RES[notification.result<br/>Key: messageId<br/>Schema: NotificationEvent]
        DLQ[notification.dlq<br/>Failed Messages]
    end

    NS -->|Publish| REQ
    REQ -->|Consume| DS
    REQ -->|Consume| AS

    DS -->|Publish SUCCESS| RES
    DS -->|Publish FAILED| DLQ
    DS -->|Publish FAILED| RES

    RES -->|Consume| TS
    RES -->|Consume| AS

    style REQ fill:#d4edda
    style RES fill:#cce5ff
    style DLQ fill:#f8d7da
```

---

## Service Dependencies

```mermaid
graph TD
    Commons[commons-shared<br/>Utilities, Base Classes,<br/>gRPC Protos & Avro Schemas]

    Gateway[gateway-service]
    Tenant[tenant-service]
    Notification[notification-service]
    Delivery[delivery-service]
    Analytics[analytics-service]

    Commons --> Gateway
    Commons --> Tenant
    Commons --> Notification
    Commons --> Delivery
    Commons --> Analytics

    Gateway -.->|REST API| Tenant
    Notification -.->|gRPC| Tenant

    style Commons fill:#e2e3e5
```

---

## Message Lifecycle States

```mermaid
stateDiagram-v2
    [*] --> Created: Client sends via API
    Created --> PENDING: Saved to DB
    PENDING --> InKafka: Published to notification.requested
    InKafka --> Delivering: Consumed by Delivery Service

    Delivering --> RetryAttempt1: Attempt 1 fails
    RetryAttempt1 --> RetryAttempt2: Backoff 500ms
    RetryAttempt2 --> RetryAttempt3: Backoff 1000ms
    RetryAttempt3 --> FAILED: Max retries (3)

    Delivering --> SENT: Success on any attempt

    SENT --> [*]: Quota incremented
    FAILED --> [*]: No quota increment

    note right of SENT
        Result published to
        notification.result
        with status: SENT
    end note

    note right of FAILED
        Sent to notification.dlq
        Result with status: FAILED
    end note
```

---

## Component Interaction - C4 Container Diagram

```mermaid
C4Container
    title Container Diagram - Notification Hub

    Person(user, "API Client", "Application using Notification Hub")

    Container_Boundary(gateway, "API Gateway") {
        Container(gw, "Gateway Service", "Spring Cloud Gateway", "Routes, authenticates, rate limits")
    }

    Container_Boundary(services, "Core Services") {
        Container(tenant, "Tenant Service", "Spring Boot", "Auth, quota, multi-tenancy")
        Container(notification, "Notification Service", "Spring Boot", "Fast API entry point")
        Container(delivery, "Delivery Service", "Spring Boot", "Background delivery workers")
        Container(analytics, "Analytics Service", "Spring Boot", "Message tracking")
    }

    Container_Boundary(infra, "Infrastructure") {
        ContainerDb(kafka, "Apache Kafka", "Message Broker", "Event streaming")
        ContainerDb(redis, "Redis", "Cache", "API keys, rate limits")
        ContainerDb(tenantDB, "PostgreSQL", "Database", "Tenant data")
        ContainerDb(notificationDB, "PostgreSQL", "Database", "Notifications")
        ContainerDb(analyticsDB, "PostgreSQL", "Database", "Messages")
    }

    Rel(user, gw, "Uses", "HTTPS/REST")
    Rel(gw, tenant, "Validates", "REST")
    Rel(gw, notification, "Routes", "REST")
    Rel(gw, analytics, "Routes", "REST")

    Rel(notification, tenant, "Checks quota", "gRPC")
    Rel(notification, kafka, "Publishes", "Avro")

    Rel(kafka, delivery, "Delivers", "Consumer")
    Rel(kafka, analytics, "Tracks", "Consumer")
    Rel(delivery, kafka, "Results", "Producer")

    Rel(tenant, tenantDB, "Reads/Writes", "JDBC")
    Rel(notification, notificationDB, "Reads/Writes", "JDBC")
    Rel(analytics, analyticsDB, "Reads/Writes", "JDBC")

    Rel(tenant, redis, "Caches", "Lettuce")
    Rel(gw, redis, "Rate limits", "Lettuce")
```

---

## How to Use These Diagrams

### 1. View in GitHub
Mermaid diagrams render automatically on GitHub.

### 2. Export as Images

**Using Mermaid CLI:**
```bash
npm install -g @mermaid-js/mermaid-cli

mmdc -i docs/diagrams/ARCHITECTURE_DIAGRAMS.md \
     -o docs/diagrams/system-architecture.png
```

### 3. Edit Diagrams

**Online Editor:** https://mermaid.live
1. Copy diagram code
2. Edit in browser
3. Export as PNG/SVG

### 4. Embed in Documentation

```markdown
![System Architecture](diagrams/system-architecture.png)
```

---

**For more diagrams, see:**
- [Sequence Diagrams](../ARCHITECTURE.md#data-flow)
- [Database Schema](../services/)
