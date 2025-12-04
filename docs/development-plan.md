# 💡 Development Plan & Demo Scenarios (Core "Big Four")

This document outlines a strategic, 3-phase approach to building the core Notification Hub platform.

---

### 🟢 Phase 1: The SaaS Foundation
**Goal:** Establish multi-tenancy and authentication.

1.  **Service to Build:** `Auth & Tenant Service`.
2.  **Key Tasks:**
    * Set up the `tenants` table in PostgreSQL with `api_key`, `plan`, and `quota_limit`.
    * Implement `POST /api/v1/tenants` to create tenants.
    * Implement internal `GET /internal/auth/validate` endpoint.
    * Integrate Redis for caching API key validations.
    * **Test:** Create a tenant, validate its API key.

---

### 🟢 Phase 2: The Asynchronous Pipeline
**Goal:** Create the main end-to-end workflow for sending a message.

1.  **Services to Build:** `Notification Orchestrator` and `Delivery Service`.
2.  **Key Tasks:**
    * Set up a Kafka broker.
    * **Orchestrator:**
        * Create `POST /api/v1/notifications` endpoint.
        * Implement a **mock call** to the Auth service (hardcode `tenantId`).
        * Generate `messageId` and publish a `NotificationRequested` event to Kafka.
        * Respond `202 Accepted`.
    * **Delivery Service:**
        * Create Kafka consumer for `notification.requested`.
        * Implement a **Mock Adapter** (e.g., `EmailAdapter`) that just logs the message and `Thread.sleep(50)`.
        * Publish a `NotificationDelivered` event (with the same `messageId`) to the `notification.result` topic.
    * **Test:** Call the Orchestrator endpoint and watch the logs/Kafka topics to see the message flow from `requested` to `result`.

---

### 🟢 Phase 3: Closing the Loop (Saga & Analytics)
**Goal:** Track message status and implement accurate quota.

1.  **Services to Build:** `Analytics & Logging Service` and update `Auth & Tenant Service`.
2.  **Key Tasks:**
    * **Analytics Service:**
        * Create Kafka consumer for both `notification.requested` and `notification.result` topics.
        * Implement logic to create (`PENDING`) and update (`DELIVERED`) records in the `messages` table.
        * Build the `GET /api/v1/messages/{id}` endpoint.
    * **Auth Service:**
        * Add a Kafka consumer to listen to `notification.result`.
        * Implement the Saga logic: If `status == DELIVERED`, update `quota_used` in the database.
    * **Test (The Full Demo):**
        1.  Call `POST /api/v1/notifications`. Get `messageId: abc-123` and `status: PENDING`.
        2.  Call `GET /api/v1/messages/abc-123`. See `status: PENDING`.
        3.  Wait 2 seconds. Call `GET /api/v1/messages/abc-123` again. See `status: DELIVERED`.
        4.  Check the `tenants` table in the database. See `quota_used` has incremented by 1.