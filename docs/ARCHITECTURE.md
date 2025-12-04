# 🏗️ System Architecture & Data Flow (Core "Big Four")

This document provides a detailed look at the core "Big Four" microservice architecture and describes the primary data flow using Kafka.

## Architectural Diagram

The system is composed of four decoupled microservices that communicate via REST and a Kafka message broker.

```
[Client App (Tenant)]
|
| REST (API Key)
v
[Notification Orchestrator] --(1. notification.requested)--\> [KAFKA]
(Validates, Generates messageId,
Responds 202 PENDING immediately)
|
\+-----------------------------------------------------------+
|
|   (2. Consumes notification.requested)
v
[Delivery Service]
(Sends to 3rd party, Retries, handles DLQ)
|
|   (3. Publishes result)
v
[KAFKA] --(4. notification.result)--\> [Analytics & Logging Service]
|                                     (Consumes result, persists state,
|                                      provides GET /messages API)
|
\+----(4. notification.result)--\> [Auth & Tenant Service]
(Consumes DELIVERED status,
securely debits quota via SAGA)

```

## 🔄 Core Data Flow: Sending a Notification

This sequence outlines the step-by-step asynchronous process.

1.  **Client App -> Notification Orchestrator**:
    * The client app sends a `POST /api/v1/notifications` request with its `API_KEY`.
    * The **Orchestrator** validates the request, checks preliminary quota (e.g., in Redis), and generates a unique `messageId`.
    * It publishes a `NotificationRequested` event (containing the `messageId`) to the `notification.requested` Kafka topic.
    * It immediately returns `202 Accepted` to the client with the `messageId`. The API call is now finished and fast.

2.  **Kafka -> Delivery Service**:
    * A `Delivery Service` instance consumes the event from the `notification.requested` topic.
    * It attempts to send the notification via the specified channel (e.g., SMTP).
    * **Retry/DLQ:** If it fails, it retries. If it fails permanently, it moves the message to the `notification.dlq` topic.

3.  **Delivery Service -> Kafka**:
    * After the final attempt (success or failure), the `Delivery Service` publishes a result event (`NotificationDelivered` or `NotificationFailed`) to the `notification.result` Kafka topic. This event includes the original `messageId`.

4.  **Kafka -> Analytics & Auth (Saga Conclusion)**:
    * The `notification.result` topic is consumed by **two** separate services:
    * **Analytics & Logging Service**: It finds the original message by `messageId` and updates its status to `DELIVERED` or `FAILED`. This updates the data for the `GET /api/v1/messages` API.
    * **Auth & Tenant Service**: It also consumes the event. If the status is `DELIVERED`, it performs the "real" quota debit (e.g., `UPDATE tenants SET quota_used = quota_used + 1`) as the final step of the Saga pattern. This ensures tenants are only charged for messages that are successfully sent.
