# 🛡️ Auth & Tenant Service - Technical Specification

This service is the authoritative source for all tenant-related information. It is responsible for managing tenant identities, handling authentication for all API requests, and enforcing usage quotas based on subscription plans.

**Recommended Tech Stack:** Spring Boot (Java), PostgreSQL, Redis

---

## Core Responsibilities

### 1. Tenant Management
This service is responsible for the lifecycle of a tenant account, from creation to deactivation.

-   **API Endpoints (Public):**
    -   `POST /api/v1/tenants`: Creates a new tenant account. This is typically called when a new user signs up. The service generates a unique `tenantId` and a secure, random `apiKey`.

-   **Data Model (PostgreSQL `tenants` table):**
    -   `tenant_id` (UUID, Primary Key): A unique identifier for the tenant.
    -   `name` (VARCHAR): The human-readable name of the tenant's application or company.
    -   `api_key` (VARCHAR, Unique, Indexed): The secret key used for authenticating API requests. Should be stored as a secure hash.
    -   `plan` (ENUM: `FREE`, `PRO`, `ENTERPRISE`): The current subscription plan of the tenant.
    -   `status` (ENUM: `ACTIVE`, `INACTIVE`, `SUSPENDED`): The current status of the tenant's account.
    -   `created_at` (TIMESTAMP), `updated_at` (TIMESTAMP): Standard audit timestamps.

### 2. High-Performance Authentication
This is the service's most critical and frequently called function. It must be extremely fast to avoid adding latency to every API call.

-   **API Endpoint (Internal, for API Gateway):**
    -   `GET /internal/auth/validate?apiKey={key}`: Validates an API key.
    -   **Request:** The API Gateway provides the plain-text API key.
    -   **Response (on success):** Returns a payload containing `tenantId`, `plan`, and `status`.
    -   **Response (on failure):** Returns a `401 Unauthorized` or `403 Forbidden` error.

-   **Performance Optimization (Redis Cache):**
    -   To avoid hitting the PostgreSQL database for every single request, the service heavily utilizes a Redis cache.
    -   **Cache Key:** `auth:api_key:{apiKey}`
    -   **Cache Value:** A serialized object containing `{ "tenantId": "...", "plan": "PRO", "status": "ACTIVE" }`.
    -   **Workflow:**
        1.  When a validation request comes in, first check Redis.
        2.  If the key exists in the cache (cache hit), return the cached data immediately.
        3.  If the key does not exist (cache miss), query the PostgreSQL database.
        4.  If the key is found in the DB, populate the Redis cache with the tenant's information and a TTL (Time-To-Live, e.g., 5-15 minutes) before returning the data.
        5.  If the key is not found in the DB, cache a "not found" response to prevent repeated database queries for invalid keys.

### 3. Quota Management and Enforcement
This service tracks and enforces the message sending limits for each tenant's plan.

-   **Mechanism (Redis Atomic Counter):**
    -   Uses Redis's `INCR` command for thread-safe, high-performance counting.
    -   **Redis Key:** `quota:usage:{tenantId}:{yyyy-mm}` (e.g., `quota:usage:t-12345:2025-10`).
    -   **Workflow:** The Notification Orchestrator will call an internal endpoint in this service before publishing to Kafka. This service will atomically increment the counter and check if it exceeds the plan's limit.

-   **Scheduled Quota Reset:**
    -   A scheduled job (e.g., a Cron job) runs at midnight UTC on the first day of every month.
    -   This job iterates through all active tenants and sets the TTL for their previous month's quota keys in Redis, effectively resetting their usage count for the new month.

### 4. Internal Management APIs
These endpoints are used by other services within the system to manage tenant state.

-   **API Endpoints (Internal):**
    -   `PUT /internal/tenants/{tenantId}/plan`: Called by the **Payment & Billing Service** after a successful plan upgrade. This updates the `plan` in PostgreSQL and invalidates the relevant Redis caches to ensure new limits are applied.
    -   `POST /internal/tenants/{tenantId}/usage/increment`: Called by the **Notification Orchestrator**. Atomically increments the usage counter for the tenant and returns whether the quota is exceeded.

### 5. Security and Auditing
-   **API Key Rotation:**
    -   Provides a public API endpoint for tenants to invalidate their current API key and generate a new one.
    -   `POST /api/v1/tenants/me/rotate-key`
-   **Audit Logging:** Logs all critical security events, such as successful/failed authentication attempts, plan changes, and API key rotations.