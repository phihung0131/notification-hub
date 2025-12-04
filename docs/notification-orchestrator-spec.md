# 🎶 Notification Orchestrator - Technical Specification

The Notification Orchestrator is the high-performance API entry point. Its *only* job is to validate requests, check preliminary quota, and publish jobs to Kafka as fast as possible. **It does not perform any I/O-bound tasks** like database writes or external API calls.

**Recommended Tech Stack:** Spring Boot (Java), Spring for Apache Kafka

---

## Core Responsibilities

### 1. Receive and Validate Notification Request
* **API Endpoint (Internal):** `POST /api/v1/notifications` (Called from API Gateway).
* **Expected Input:** `X-Tenant-Id` header, and a JSON body.
* **Initial Validation:** Checks if `channel` is supported, `to` field is present, etc.

### 2. Enforce Preliminary Quota (Fast Check)
* **Workflow:**
    1.  Makes a synchronous internal call to the **Auth & Tenant Service**: `GET /internal/tenants/{tenantId}/check-quota`.
    2.  This is a fast, cache-based check.
* **Action on Failure:** If quota is exceeded, immediately returns `429 Too Many Requests`.

### 3. Prepare Content
* **No Template Service:** This service **does not** call a Template Service.
* **Logic:** It either accepts raw `content` from the request body or performs simple string formatting (e.g., `String.format("Hello %s", data.get("name"))`). This must be extremely fast and in-memory.

### 4. Asynchronous Event Publishing
* **Action:** Publishes a message to the `notification.requested` Kafka topic.
* **Message ID:** It **generates a unique `messageId` (UUID)**. This ID is the "Correlation ID" for the entire lifecycle of the message.
* **Kafka Message Payload (JSON):**
    ```json
    {
      "messageId": "msg-a4b1c2d3-e4f5-4a6b-8c7d-9e8f7a6b5c4d",
      "tenantId": "t-12345",
      "channel": "email",
      "recipient": "recipient@example.com",
      "renderedContent": "Hello Alex",
      "timestamp": "..."
    }
    ```

### 5. Immediate Client Response
* As soon as the message is published to Kafka (an in-memory buffer), the service responds.
* **HTTP Status:** `202 Accepted`
* **Response Body (JSON):**
    ```json
    {
      "messageId": "msg-a4b1c2d3-e4f5-4a6b-8c7d-9e8f7a6b5c4d",
      "status": "PENDING"
    }
    ```