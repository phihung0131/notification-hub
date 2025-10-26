# 📊 Analytics & Logging Service - Technical Specification

The Analytics & Logging Service acts as the system's "single source of truth" for the status and history of every notification. It is responsible for consuming lifecycle events from Kafka, persisting them for long-term storage, and providing an API for tenants to query the status of their messages.

**Recommended Tech Stack:** Spring Boot (Java), Spring for Apache Kafka, PostgreSQL (for structured data) or Elasticsearch (for advanced search and filtering).

---

## Core Responsibilities

### 1. Consume and Process Message Lifecycle Events
This service is a Kafka consumer that listens to multiple topics to build a complete picture of a message's journey.

-   **Subscribed Topics:**
    -   `notification.requested`: Consumed to create the initial record for a message with a `PENDING` status.
    -   `notification.result`: Consumed to receive the final outcome (`DELIVERED` or `FAILED`) from the Delivery Service and update the existing record.
-   **Workflow:**
    1.  When a `notification.requested` event is received, the service creates a new entry in its database with the `messageId`, `tenantId`, `channel`, `recipient`, and sets the status to `PENDING`.
    2.  When a `notification.result` event is received, the service finds the existing record using the `messageId` and updates its `status`, `deliveredAt` or `failedAt` timestamp, and any error details. This update operation must be idempotent.

### 2. Data Persistence
The service stores the state of every message in a durable database, optimized for fast lookups by `messageId` and efficient querying by tenants.

-   **Data Model (`messages` table in PostgreSQL):**
    -   `message_id` (UUID, Primary Key, Indexed): The unique identifier for the message.
    -   `tenant_id` (UUID, Indexed): The ID of the tenant who sent the message.
    -   `status` (ENUM: `PENDING`, `DELIVERED`, `FAILED`): The current status of the message.
    -   `channel` (VARCHAR): The delivery channel (e.g., "email").
    -   `recipient` (VARCHAR): The address/ID of the recipient.
    -   `created_at` (TIMESTAMP): When the request was first received by the Orchestrator.
    -   `updated_at` (TIMESTAMP): When the record was last updated (e.g., when the final status arrived).
    -   `delivered_at` (TIMESTAMP, nullable): Timestamp of successful delivery.
    -   `failed_at` (TIMESTAMP, nullable): Timestamp of terminal failure.
    -   `failure_reason` (TEXT, nullable): Details about why the delivery failed.
-   **Database Indexing:** A composite index on `(tenant_id, created_at, status)` is crucial for performant filtering and sorting in the tenant-facing API.

### 3. Tenant-Facing Query API
This is the primary feature exposed to users. It allows them to programmatically check the status of their notifications and retrieve historical data.

-   **API Endpoints (Public, routed via API Gateway):**
    -   `GET /api/v1/messages/{id}`: Retrieves the detailed status of a single message.
        -   **Response:**
            ```json
            {
              "messageId": "msg-a4b1c2d3...",
              "status": "DELIVERED",
              "channel": "email",
              "recipient": "recipient@example.com",
              "createdAt": "2025-10-26T15:30:00Z",
              "deliveredAt": "2025-10-26T15:30:05Z"
            }
            ```
    -   `GET /api/v1/messages`: Allows tenants to query their message history with filters. The `tenantId` is inferred from the API key at the Gateway level and should not be a query parameter.
        -   **Query Parameters:** `status` (e.g., `FAILED`), `channel`, `dateFrom`, `dateTo`, `limit`, `offset` (for pagination).
        -   **Response:** A paginated list of message objects.

### 4. System Metrics and Monitoring
The service provides crucial data for monitoring the overall health and performance of the notification delivery pipeline.

-   **Metrics Export:** It exports metrics to Prometheus for visualization in Grafana.
-   **Key Metrics:**
    -   `notifications_processed_total{status="delivered", channel="email"}`: A counter for successfully delivered messages, labeled by channel and status.
    -   `notifications_processed_total{status="failed", channel="telegram"}`: A counter for failed messages.
    -   `notification_end_to_end_latency_seconds`: A histogram measuring the time from when a message was `created_at` to when it was finally `updated_at` with a terminal status (`DELIVERED` or `FAILED`). This is a key performance indicator (KPI).

### 5. Alerting (Optional Advanced Feature)
By analyzing the stream of result events, this service can power an alerting system.

-   **Rule-based Alerting:** It can be configured with rules, such as: "If the failure rate for the 'email' channel exceeds 10% over a 5-minute window, trigger an alert."
-   **Integration:** These alerts can be sent to an operations team via PagerDuty, Slack, or other monitoring tools.