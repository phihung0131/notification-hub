# 🚪 API Gateway - Technical Specification

The API Gateway serves as the single, unified entry point for all external client requests. Its primary role is to protect, route, and manage traffic to the backend microservices, abstracting the internal system complexity from the end-user.

**Recommended Tech Stack:** Spring Cloud Gateway

---

## Core Responsibilities

### 1. Centralized Entry Point
The Gateway is the only component exposed to the public internet. It provides a consistent and stable API contract for clients, even as internal services are developed, scaled, or refactored independently.

-   **Public Endpoints:**
    -   `POST /api/v1/notifications`
    -   `GET /api/v1/messages/{id}`
    -   `POST /api/v1/payment/upgrade`
    -   `GET /api/v1/templates`

### 2. Authentication & Authorization
This is the first line of defense. Every request passing through the Gateway must be authenticated.

-   **Workflow:**
    1.  Intercepts every incoming request.
    2.  Extracts the API Key from the `Authorization: Bearer <API_KEY>` header.
    3.  Makes a synchronous, low-latency internal call (preferably gRPC) to the **Auth & Tenant Service** to validate the key.
    4.  The Auth Service checks the key's validity and the tenant's current usage quota.
    5.  **On Failure:** If the key is invalid, expired, or the tenant has exceeded their quota, the Gateway immediately rejects the request with an appropriate error code (e.g., `401 Unauthorized`, `403 Forbidden`, `429 Too Many Requests`).
    6.  **On Success:** The Gateway proceeds to the next step.

### 3. Request Enrichment & Transformation
After successful authentication, the Gateway modifies the request before forwarding it to the appropriate backend service.

-   **Header Injection:** It adds an `X-Tenant-Id` header to the request. This securely identifies the tenant for all downstream services, eliminating the need for them to perform their own authentication checks.
-   **Path Rewriting:** It can rewrite URL paths to match the internal service routing rules.

### 4. Dynamic Routing & Load Balancing
The Gateway is responsible for forwarding incoming requests to the correct microservice. This routing logic is centralized and can be updated without affecting clients.

-   **Routing Configuration:**
    -   `POST /api/v1/notifications/**`  ->  `notification-orchestrator-service`
    -   `GET /api/v1/messages/**`       ->  `analytics-logging-service`
    -   `POST /api/v1/tenants/**`       ->  `auth-tenant-service`
    -   `POST /api/v1/payment/**`       ->  `payment-billing-service`
    -   `GET /api/v1/templates/**`      ->  `template-service`
-   **Load Balancing:** When multiple instances of a backend service are running, the Gateway will automatically distribute the traffic among them.

### 5. Rate Limiting
To prevent abuse and ensure fair resource allocation among tenants, the Gateway enforces rate limits.

-   **Implementation:** Uses a Redis-backed token bucket or leaky bucket algorithm.
-   **Logic:** Each tenant is allocated a certain number of requests per minute (e.g., 100 req/min for a FREE plan).
-   **Action:** If a tenant exceeds this limit, the Gateway rejects subsequent requests with an `HTTP 429 Too Many Requests` status code.

### 6. Observability (Logging & Monitoring)
The Gateway is a critical point for gathering metrics and logs about system usage and performance.

-   **Logging:** Logs detailed information for every request, including:
    -   `tenantId`
    -   Request path and method
    -   Response status code
    -   Request-response latency (duration)
    -   Origin IP address
-   **Metrics:** Exports key metrics to a monitoring system like Prometheus:
    -   `requests_total` (counter, with labels for path, method, status)
    -   `requests_latency_seconds` (histogram)
    -   `upstream_service_error_rate` (gauge)

### 7. API Aggregation (Optional Advanced Feature)
For certain use cases, the Gateway can act as an aggregator, combining data from multiple downstream services into a single response.

-   **Example Scenario:** A client requests `GET /api/v1/dashboard`.
    -   The Gateway could internally call:
        1.  `Auth & Tenant Service` to get the current plan details.
        2.  `Analytics & Logging Service` to get the message count for the current month.
    -   It would then merge these two pieces of information into a single JSON response for the client, reducing the number of round trips the client needs to make.