# 🏗️ System Architecture & Data Flow

This document provides a detailed look at the overall architecture of the Notification Hub and describes the primary data flow for the core use case: sending a notification.

## Architectural Diagram

The system is composed of several decoupled microservices that communicate via REST, gRPC, and a Kafka message broker. This design ensures scalability, resilience, and maintainability.

```
[Client Apps (E-com, Fintech)]
|
| REST (API Key)
v
[API Gateway]
|
| REST/gRPC
+--------------------------------+
|                                |
v                                v
[Auth & Tenant Service] --gRPC--> [Notification Orchestrator]
(manages quota, plan)               (validates, renders, publishes)
|                                |
| REST (upgrade event)           | Kafka (notification.requested event)
v                                v
[Payment & Billing Service]       [Delivery Service(s)]
(handles Stripe/mock payments)      (consumers for Email/Telegram/...)
|
| Kafka (notification.result event)
v
[Analytics & Logging Service]
(provides status query API)
```

## 🔄 Core Data Flow: Sending a Notification

This sequence outlines the step-by-step process that occurs when a client application sends a notification request.

1.  **Client App -> API Gateway**: The client application initiates a request by sending a `POST /api/v1/notifications` request. The request must include a valid `Authorization: Bearer <API_KEY>` header.

2.  **API Gateway -> Auth & Tenant Service**:
    *   The API Gateway intercepts the request and acts as a security checkpoint.
    *   It makes an internal gRPC or REST call to the **Auth & Tenant Service** to validate the provided API Key.
    *   This service checks if the key is valid, belongs to an active tenant, and if the tenant has not exceeded their monthly usage quota.
    *   If authentication or authorization fails, the Gateway immediately rejects the request with an appropriate HTTP status code (e.g., `401 Unauthorized` or `429 Too Many Requests`).

3.  **API Gateway -> Notification Orchestrator**:
    *   Upon successful validation, the API Gateway enriches the request by adding an `X-Tenant-Id` header. This prevents downstream services from needing to re-validate the token.
    *   The Gateway then forwards the modified request to the **Notification Orchestrator**.

4.  **Notification Orchestrator**:
    *   This service receives the request and performs business logic validation (e.g., checks if the `channel` is supported, validates the request payload).
    *   It calls the **Template Service** (if a template is specified) to render the final message content using the provided data.
    *   It generates a unique `messageId` for tracking.
    *   It then serializes the notification details into a message and publishes a `NotificationRequested` event to a dedicated Kafka topic.
    *   Crucially, it immediately returns a response to the client with the `messageId` and a `PENDING` status, making the API call non-blocking and fast. `{"messageId": "msg-xyz123", "status": "PENDING"}`.

5.  **Kafka -> Delivery Service(s)**:
    *   One or more instances of the **Delivery Service** are subscribed to the `NotificationRequested` Kafka topic as part of a consumer group.
    *   A consumer instance picks up the event from the topic.
    *   Based on the `channel` field in the message (e.g., "email", "telegram"), the service routes the job to the appropriate adapter (e.g., `EmailAdapter`, `TelegramAdapter`).

6.  **Delivery Service -> External Provider**:
    *   The selected adapter makes the final API call to the third-party service provider (e.g., an SMTP server for email, the Telegram Bot API).
    *   **Retry Mechanism**: If the delivery fails due to a transient error (e.g., network timeout, provider API is temporarily down), the service will attempt to retry the delivery using a predefined strategy (e.g., exponential backoff).
    *   **Dead Letter Queue (DLQ)**: If the message consistently fails delivery after all retry attempts, it is moved to a `notifications.DLQ` topic for manual inspection and debugging.

7.  **Delivery Service -> Kafka**:
    *   After the final delivery attempt (either success or failure), the Delivery Service publishes a result event (`NotificationDelivered` or `NotificationFailed`) to a separate Kafka topic, such as `notifications.result`. This event includes the original `messageId`, `tenantId`, final status, and timestamps.

8.  **Kafka -> Analytics & Logging Service**:
    *   The **Analytics & Logging Service** consumes events from the `notifications.result` topic.
    *   It persists the final state and the complete lifecycle of the message into its database (e.g., Elasticsearch or PostgreSQL) for long-term storage and querying.

9.  **Client App -> API Gateway -> Analytics & Logging Service**:
    *   At any point after step 4, the client application can use the `messageId` to query the status of their notification by making a `GET /api/v1/messages/{messageId}` request.
    *   The API Gateway routes this request to the **Analytics & Logging Service**, which looks up the record in its database and returns the current status (`PENDING`, `DELIVERED`, or `FAILED`).

