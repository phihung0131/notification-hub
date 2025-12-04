# 📊 Analytics & Logging Service - Technical Specification

The Analytics & Logging Service acts as the system's "single source of truth" for the status and history of every notification.

**Recommended Tech Stack:** Spring Boot (Java), Spring for Apache Kafka, PostgreSQL

---

## Core Responsibilities

### 1. Consume and Process Message Lifecycle Events
* This service is a Kafka consumer that listens to multiple topics to build a complete picture of a message's journey.
* **Subscribed Topics:**
    * `notification.requested`: Consumed to **create** the initial record for a message with `status: PENDING`. The `messageId` from the event is used as the Primary Key.
    * `notification.result`: Consumed to **update** the existing record's status to `DELIVERED` or `FAILED` based on the `messageId`.
* **Idempotency:** The update operation must be idempotent.

### 2. Data Persistence
* **Data Model (`messages` table in PostgreSQL):**
    * `message_id` (UUID, Primary Key, Indexed): The ID created by the Orchestrator.
    * `tenant_id` (UUID, Indexed)
    * `status` (ENUM: `PENDING`, `DELIVERED`, `FAILED`)
    * ... (timestamps, etc.)

### 3. Tenant-Facing Query API
* This is the primary feature exposed to users.
* **API Endpoints (Public):**
    * `GET /api/v1/messages/{id}`: Retrieves the detailed status of a single message.
    * `GET /api/v1/messages`: Allows tenants to query their message history.