# 🛡️ Auth & Tenant Service - Technical Specification

This service is the authoritative source for tenant identities, authentication, and quota management. It implements a Saga pattern for accurate, asynchronous quota deduction.

**Recommended Tech Stack:** Spring Boot (Java), PostgreSQL, Redis, Spring for Apache Kafka

---

## Core Responsibilities

### 1. Tenant Management
* **API Endpoints (Public):** `POST /api/v1/tenants` (Creates tenant, generates API Key).
* **Data Model (PostgreSQL `tenants` table):**
    * `tenant_id` (UUID, PK)
    * `api_key` (VARCHAR, Unique, Indexed, Hashed)
    * `plan` (ENUM: `FREE`, `PRO`)
    * `status` (ENUM: `ACTIVE`, `SUSPENDED`)
    * `quota_limit` (INT): Max messages per month.
    * `quota_used` (INT): **Accurate count, updated by Saga.**
    * `created_at`, `updated_at`

### 2. High-Performance Authentication
* **API Endpoint (Internal):** `GET /internal/auth/validate?apiKey={key}`.
* **Performance:** Heavily cached in Redis (`auth:api_key:{apiKey}` -> `tenant_id`, `plan`, `status`) to avoid DB hits on every request.

### 3. Preliminary Quota Check (Cache-based)
* **API Endpoint (Internal):** `GET /internal/tenants/{tenantId}/check-quota`.
* **Mechanism:** Called by the **Notification Orchestrator**. This is a *fast, preliminary check*.
* **Logic:** It checks a Redis counter (`quota:check:{tenantId}`). If this counter exceeds the `quota_limit`, it returns `429 Too Many Requests`. This is meant to quickly reject requests, not for accurate billing. This Redis counter is reset periodically (e.g., hourly) from the database's `quota_used` value.

### 4. Quota Deduction (Saga Pattern)
* This service **does not** have an API for decrementing quota. Quota is debited asynchronously.
* **Kafka Consumer:** The service operates as a Kafka consumer subscribing to the `notification.result` topic.
* **Topic:** `notification.result`
* **Logic (The Saga's final step):**
    1.  Consumes the event (`NotificationDelivered` or `NotificationFailed`).
    2.  If `status` is `DELIVERED`:
        * It performs a database transaction: `UPDATE tenants SET quota_used = quota_used + 1 WHERE tenant_id = ?`.
        * This ensures the tenant is only billed for messages that were confirmed sent.
    3.  If `status` is `FAILED`:
        * It does nothing. The tenant is not charged. This is the "compensation" for the failed send.