# 📝 Template Service - Technical Specification

The Template Service is a dedicated microservice responsible for managing and rendering dynamic message templates. By centralizing template logic, tenants can create, manage, and reuse their notification content without needing to hardcode it in their application logic. This service is called by the Notification Orchestrator during the message preparation phase.

**Recommended Tech Stack:** Spring Boot (Java), PostgreSQL (for storing templates), Redis (for caching), and a templating engine like FreeMarker or Mustache.

---

## Core Responsibilities

### 1. Template Management (CRUD Operations)
This service provides a full set of APIs for tenants to manage their own templates.

-   **API Endpoints (Public):**
    -   `POST /api/v1/templates`: Creates a new template.
    -   `GET /api/v1/templates`: Lists all templates belonging to the authenticated tenant.
    -   `GET /api/v1/templates/{templateId}`: Retrieves a single template by its ID.
    -   `PUT /api/v1/templates/{templateId}`: Updates an existing template.
    -   `DELETE /api/v1/templates/{templateId}`: Deletes a template.

-   **Data Model (`templates` table in PostgreSQL):**
    -   `template_id` (UUID, Primary Key)
    -   `tenant_id` (UUID, Indexed): Ensures templates are isolated per tenant.
    -   `name` (VARCHAR): A unique, human-readable name for the template (e.g., `user_welcome`, `otp_code`). A unique constraint should be applied on `(tenant_id, name)`.
    -   `content` (TEXT): The template body, containing placeholders.
        -   Example: `Hello {{username}}, your OTP is {{code}}. It is valid for 5 minutes.`
    -   `created_at` (TIMESTAMP), `updated_at` (TIMESTAMP)

-   **Example `POST` Request Body:**
    ```json
    {
      "name": "user_welcome",
      "content": "Welcome to our service, {{username}}! We are glad to have you."
    }
    ```

### 2. High-Performance Template Rendering
This is the most critical function of the service, designed to be called internally by the Notification Orchestrator. It must be extremely fast to avoid adding significant latency to the notification pipeline.

-   **API Endpoint (Internal):**
    -   `POST /internal/templates/render`
-   **Request Body:**
    ```json
    {
      "tenantId": "t-12345",
      "templateName": "user_welcome",
      "data": {
        "username": "Alex"
      }
    }
    ```
-   **Workflow:**
    1.  The service receives a render request from the Orchestrator.
    2.  It first attempts to fetch the compiled template from a Redis cache.
    3.  If it's a cache miss, it queries the PostgreSQL database for the template content using `tenantId` and `templateName`.
    4.  It then uses a templating engine (like FreeMarker) to substitute the placeholders (e.g., `{{username}}`) with the values from the `data` object.
    5.  The compiled template is stored in the cache for subsequent requests.
    6.  The final, rendered string is returned in the response.

-   **Response Body:**
    ```json
    {
      "renderedContent": "Welcome to our service, Alex! We are glad to have you."
    }
    ```

### 3. Caching for Performance
Database lookups can be a bottleneck under high load. Aggressive caching is essential for the rendering endpoint.

-   **Cache Strategy:** Cache the raw template content fetched from the database.
-   **Cache Key:** `template:{tenantId}:{templateName}`
    -   Example: `template:t-12345:user_welcome`
-   **Cache Invalidation:** When a tenant updates or deletes a template using the `PUT` or `DELETE` endpoints, the service must explicitly invalidate (delete) the corresponding key from the Redis cache. This ensures that the next render request fetches the fresh content from the database.

### 4. Multi-language and Multi-channel Support (Advanced Feature)
For a more advanced implementation, the data model can be extended to support different versions of a template.

-   **Extended Data Model (`template_versions` table):**
    -   `template_version_id` (PK)
    -   `template_id` (FK to `templates` table)
    -   `language` (VARCHAR, e.g., "en-US", "vi-VN"): The language of the template.
    -   `channel` (VARCHAR, e.g., "email", "sms"): Allows different content for different channels (e.g., a shorter SMS version).
    -   `content` (TEXT)

-   The internal render API would then be updated to accept `language` and `channel` parameters to retrieve the correct template version.

---

Như vậy là chúng ta đã hoàn thành việc đặc tả chi tiết cho tất cả các service chính! Bạn có muốn tôi tạo file cuối cùng, `development-plan.md`, để tóm tắt lộ trình phát triển và các kịch bản demo không?