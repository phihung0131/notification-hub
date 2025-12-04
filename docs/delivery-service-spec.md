# 🚚 Delivery Service(s) - Technical Specification

The Delivery Service is the background workhorse. It consumes jobs from Kafka, dispatches them to third-party providers, and reports the final outcome.

**Recommended Tech Stack:** Spring Boot (Java), Spring for Apache Kafka

---

## Core Responsibilities

### 1. Kafka Consumer Group
* **Subscribed Topic:** `notification.requested`
* **Consumer Group ID:** `delivery-service-group`
* **Scalability:** Allows multiple instances to run in parallel, processing messages concurrently.

### 2. Message Consumption and Routing
* Consumes the JSON message from the Orchestrator.
* **Adapter Pattern:** Uses a specific adapter based on the `channel` field (e.g., `EmailAdapter`, `TelegramAdapter`).
* **Mock Adapter:** For testing, an `EmailAdapter` can be used to just log the message and simulate a successful send.

### 3. Resiliency: Retry and Dead Letter Queue (DLQ)
* **Retry Logic:** If a send fails with a transient error (e.g., `5xx` server error), the service will **not** commit the Kafka offset, allowing the message to be re-delivered and retried.
* **Dead Letter Queue (DLQ):**
    * If a message fails with a non-retryable error (e.g., `4xx` client error) or fails after 3-5 retries:
    * **Action:** The service publishes the failed message (with error details) to the `notification.dlq` topic for manual inspection.
    * This prevents a "poison pill" message from blocking the queue.

### 4. Publish Delivery Result (Crucial for Saga)
* After the final delivery attempt (either success or terminal failure), the service **must** report the outcome.
* **Action:** It publishes a new event to the `notification.result` topic.
* **Kafka Topic:** `notification.result`
* **Success Payload (`NotificationDelivered`):**
    ```json
    { "messageId": "...", "tenantId": "...", "status": "DELIVERED", ... }
    ```
* **Failure Payload (`NotificationFailed`):**
    ```json
    { "messageId": "...", "tenantId": "...", "status": "FAILED", "reason": "...", ... }
    ```
* This event is consumed by both the `Analytics Service` and the `Auth Service`.