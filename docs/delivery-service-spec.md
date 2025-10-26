# 🚚 Delivery Service(s) - Technical Specification

The Delivery Service is the workhorse of the Notification Hub. It is a highly scalable, resilient background processor responsible for consuming notification jobs from Kafka and dispatching them to the appropriate third-party providers. This service can be scaled horizontally by running multiple instances to handle increased load.

**Recommended Tech Stack:** Spring Boot (Java) with Spring for Apache Kafka, or Go with Sarama/Confluent-Kafka-Go (for a polyglot approach). Redis for idempotency checks.

---

## Core Responsibilities

### 1. Kafka Consumer Group
The service operates as a consumer within a Kafka consumer group, subscribing to the primary notification job topic.

-   **Subscribed Topic:** `notification.requested`
-   **Consumer Group ID:** `delivery-service-group`
-   **Scalability:** By using a consumer group, multiple instances of the Delivery Service can run in parallel. Kafka will automatically balance the partitions of the topic among the instances, allowing the system to process messages concurrently and scale throughput horizontally.

### 2. Message Consumption and Routing
Each instance continuously polls Kafka for new messages.

-   **Message Payload:** It consumes the JSON message published by the Notification Orchestrator.
    ```json
    {
      "messageId": "msg-a4b1c2d3-e4f5-4a6b-8c7d-9e8f7a6b5c4d",
      "tenantId": "t-12345",
      "channel": "email",
      "recipient": "recipient@example.com",
      "renderedContent": "Hello Alex, welcome...",
      "timestamp": "2025-10-26T15:30:00Z"
    }
    ```
-   **Channel-based Routing (Adapter Pattern):** Upon receiving a message, the service inspects the `channel` field and delegates the task to a specific "Adapter" responsible for handling that channel. This makes the system extensible.
    -   `channel: "email"` -> `EmailAdapter` (uses SMTP or a service like SendGrid)
    -   `channel: "telegram"` -> `TelegramAdapter` (uses the Telegram Bot API)
    -   `channel: "slack"` -> `SlackAdapter` (uses Slack Incoming Webhooks)
    -   `channel: "discord"` -> `DiscordAdapter` (uses Discord Webhooks)

### 3. Idempotency Handling
To prevent duplicate message delivery in case of Kafka re-delivery or other failures, the service must ensure that each `messageId` is processed only once.

-   **Mechanism:** Uses Redis to track the processing status of a message.
-   **Workflow:**
    1.  Before attempting to send the message, the service checks for the existence of a specific key in Redis.
    2.  **Redis Key:** `processing:lock:{messageId}`
    3.  **Action:** It attempts to set this key with a short Time-To-Live (TTL), for example, 5 minutes.
    4.  If the `SET NX` (set if not exists) command succeeds, it means this is the first time this message is being processed, and the service proceeds.
    5.  If the command fails (the key already exists), it means another worker is already processing this message (or has already completed it). The service should then skip this message and commit the Kafka offset to avoid reprocessing.

### 4. Resiliency: Retry and Dead Letter Queue (DLQ)
Communication with third-party APIs is inherently unreliable. The service must be able to handle transient failures gracefully.

-   **Retry Logic:**
    -   If an adapter fails to send a message due to a retryable error (e.g., `5xx` server error, network timeout), the service will not commit the Kafka offset immediately.
    -   Kafka's consumer logic will automatically re-deliver the message after a short delay.
    -   For more advanced control, a non-blocking retry mechanism can be implemented using separate retry topics and exponential backoff delays.
-   **Dead Letter Queue (DLQ):**
    -   If a message fails repeatedly (e.g., after 3-5 attempts) or fails with a non-retryable error (e.g., `4xx` client error, like an invalid recipient address), it must be moved out of the main processing queue to prevent blocking other messages.
    -   **Action:** The service publishes the failed message, along with the error details, to a separate Kafka topic.
    -   **DLQ Topic:** `notification.dlq`
    -   This allows developers to inspect and diagnose failed messages without impacting the main system flow.

### 5. Publish Delivery Result
After the final delivery attempt (whether it was a success or a terminal failure), the service must report the outcome.

-   **Action:** It publishes a new event to a result topic.
-   **Kafka Topic:** `notification.result`
-   **Success Message Payload (`NotificationDelivered`):**
    ```json
    {
      "messageId": "msg-a4b1c2d3-e4f5-4a6b-8c7d-9e8f7a6b5c4d",
      "tenantId": "t-12345",
      "status": "DELIVERED",
      "channel": "email",
      "deliveredAt": "2025-10-26T15:30:05Z"
    }
    ```
-   **Failure Message Payload (`NotificationFailed`):**
    ```json
    {
      "messageId": "msg-a4b1c2d3-e4f5-4a6b-8c7d-9e8f7a6b5c4d",
      "tenantId": "t-12345",
      "status": "FAILED",
      "channel": "email",
      "reason": "Invalid recipient email address",
      "failedAt": "2025-10-26T15:30:02Z"
    }
    ```
-   This event is consumed by the **Analytics & Logging Service** to provide end-to-end message tracking.