# 💡 Development Plan & Demo Scenarios

This document outlines a strategic, phased approach to building the Notification Hub platform. The goal is to build a functional end-to-end slice of the system first (a Minimum Viable Product) and then iteratively add features and complexity. This approach ensures that you have a demonstrable product at each stage.

## Development Roadmap

The recommended order of development is designed to build foundational services first, ensuring that each new service has the necessary dependencies already in place.

---

### 🟢 Phase 1: The SaaS Core (Authentication & Tenancy)
**Goal:** Establish the foundation of the multi-tenant system.

1.  **Service to Build:** `Auth & Tenant Service`.
2.  **Key Tasks:**
    -   Set up the PostgreSQL database with the `tenants` table.
    -   Implement the `POST /api/v1/tenants` endpoint to create new tenants and generate API keys.
    -   Implement the internal `GET /internal/auth/validate` endpoint for API key validation.
    -   Integrate Redis for caching `apiKey -> tenantId` mappings to ensure high performance.
    -   **Test:** Use a tool like Postman or Insomnia to create a tenant and validate its API key.

---

### 🟢 Phase 2: The Core Notification Pipeline
**Goal:** Create the main asynchronous workflow for sending a message.

1.  **Services to Build:** `Notification Orchestrator` and `Delivery Service`.
2.  **Key Tasks:**
    -   Set up a Kafka broker.
    -   **Orchestrator:**
        -   Create the `POST /api/v1/notifications` endpoint.
        -   Initially, hardcode a call to the Auth & Tenant service (or a mock) to get the tenant ID.
        -   Publish a `NotificationRequested` event to Kafka.
    -   **Delivery Service:**
        -   Create a Kafka consumer to listen to the `notification.requested` topic.
        -   Implement at least one delivery adapter (e.g., `TelegramAdapter` or `EmailAdapter` using Mailtrap for easy testing).
        -   Publish a `NotificationDelivered` or `NotificationFailed` event to the `notification.result` topic.
    -   **Test:** Call the Orchestrator endpoint and verify that a message is actually sent to your test Telegram account or Mailtrap inbox.

---

### 🟢 Phase 3: Closing the Loop (Tracking & Analytics)
**Goal:** Allow users to see the status of their sent messages.

1.  **Service to Build:** `Analytics & Logging Service`.
2.  **Key Tasks:**
    -   Set up the `messages` table in the database.
    -   Create a Kafka consumer to listen to both `notification.requested` and `notification.result` topics.
    -   Implement the logic to create and update message records based on these events.
    -   Build the `GET /api/v1/messages/{id}` endpoint.
    -   **Test:** Send a message (from Phase 2), get the `messageId` from the Orchestrator's response, and then use the new endpoint to query its status, watching it change from `PENDING` to `DELIVERED`.

---

### 🟢 Phase 4: Adding Flexibility (Templates)
**Goal:** Decouple message content from the client's code.

1.  **Service to Build:** `Template Service`.
2.  **Key Tasks:**
    -   Implement the full CRUD API for templates (`POST`, `GET`, `PUT`, `DELETE`).
    -   Implement the internal `/internal/templates/render` endpoint with Redis caching.
    -   **Integration:** Modify the `Notification Orchestrator` to call the Template Service for rendering instead of using hardcoded content.
    -   **Test:** Create a template via the API, then send a notification using that template's name.

---

### 🟢 Phase 5: Monetization (Payments & Billing)
**Goal:** Implement the commercial aspect of the SaaS.

1.  **Service to Build:** `Payment & Billing Service`.
2.  **Key Tasks:**
    -   Implement the `POST /api/v1/payment/upgrade` endpoint.
    -   Integrate with the Stripe API (in test mode).
    -   Implement the internal call to the `Auth & Tenant Service` to update a tenant's plan upon successful payment.
    -   **Integration:** Modify the `Auth & Tenant Service` to enforce quota limits based on the tenant's plan.
    -   **Test:** Test the full upgrade flow described in the demo scenarios below.

---

### 🔴 Phase 6: Unifying the System (API Gateway)
**Goal:** Place all public-facing services behind a single, secure entry point. This is done last because the Gateway is only useful once the backend services exist.

1.  **Service to Build:** `API Gateway`.
2.  **Key Tasks:**
    -   Configure routing rules for all public endpoints.
    -   Implement the authentication filter that calls the `Auth & Tenant Service`.
    -   Configure rate limiting using Redis.
    -   **Test:** Re-run all previous tests, but this time, send the requests to the Gateway's address instead of directly to the individual services.

---

## 🎯 Key Demo Scenarios (For CV/Portfolio)

This is a step-by-step story that showcases all the major features of the completed system.

1.  **New Tenant Onboarding:**
    -   Call `POST /api/v1/tenants` to register a new tenant.
    -   **Result:** Receive a new API key. The tenant starts on the `FREE` plan with a 1000 message/month quota.

2.  **Sending Notifications:**
    -   Use the new API key to send a Telegram message and an Email via the `POST /api/v1/notifications` endpoint.
    -   **Result:** The API responds immediately with a `PENDING` status and `messageId`. The messages arrive successfully.

3.  **Tracking Status:**
    -   Use the `messageId` from the previous step to call `GET /api/v1/messages/{id}`.
    -   **Result:** The API returns the final status: `DELIVERED`.

4.  **Exceeding Quota:**
    -   (Simulate this by manually setting the usage counter in Redis to exceed the limit).
    -   Attempt to send another notification.
    -   **Result:** The API Gateway rejects the request with an `HTTP 429 Too Many Requests` error and a message: "Quota exceeded. Please upgrade your plan."

5.  **Upgrading the Plan:**
    -   Call `POST /api/v1/payment/upgrade` with a test Stripe token to upgrade to the `PRO` plan.
    -   **Result:** The API responds with a success message. The tenant's plan in the `Auth & Tenant Service` is now `PRO`, and their quota is increased/reset.

6.  **Resuming Service:**
    -   Attempt to send the same notification that previously failed.
    -   **Result:** The message is now sent successfully, demonstrating that the plan upgrade worked.