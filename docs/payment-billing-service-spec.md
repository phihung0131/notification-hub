# 💳 Payment & Billing Service - Technical Specification

The Payment & Billing Service handles all monetary aspects of the platform. It manages subscription plans, processes payments for plan upgrades, and communicates with the Auth & Tenant Service to update a tenant's capabilities upon successful payment.

**Recommended Tech Stack:** Spring Boot (Java), Stripe API SDK (for payment processing), PostgreSQL.

---

## Core Responsibilities

### 1. Plan Management
This service is the source of truth for all available subscription plans, their features, and pricing.

-   **API Endpoint (Public):**
    -   `GET /api/v1/plans`: Returns a list of all available subscription plans that a user can choose from.
-   **Example Response:**
    ```json
    [
      {
        "planId": "FREE",
        "name": "Free Tier",
        "price": 0,
        "currency": "USD",
        "features": {
          "monthlyQuota": 1000,
          "channels": ["Email", "Telegram"]
        }
      },
      {
        "planId": "PRO",
        "name": "Pro Tier",
        "price": 9.99,
        "currency": "USD",
        "features": {
          "monthlyQuota": 50000,
          "channels": ["Email", "Telegram", "Slack", "Discord"]
        }
      }
    ]
    ```
-   **Plan Configuration:** Plan details should be managed in a configuration file or a database table for easy updates without redeploying the service.

### 2. Payment Flow Integration
The service orchestrates the process of a tenant upgrading their plan.

-   **API Endpoint (Public):**
    -   `POST /api/v1/payment/upgrade`: Initiates the plan upgrade process for the authenticated tenant.
-   **Request Body:**
    ```json
    {
      "newPlanId": "PRO",
      "paymentMethodToken": "tok_1Lq3gH2eZvKYlo2C...(A one-time token from a payment provider like Stripe.js)" 
    }
    ```
-   **Workflow:**
    1.  The service receives the upgrade request. The `tenantId` is already available from the `X-Tenant-Id` header provided by the API Gateway.
    2.  It validates that the `newPlanId` is a valid, upgradeable plan.
    3.  It communicates with the payment provider's API (e.g., Stripe) using the `paymentMethodToken` to create a charge or a subscription.
    4.  **On Payment Failure:** If the payment provider rejects the transaction, the service returns an `HTTP 400 Bad Request` with an error message like `PAYMENT_FAILED`. The tenant's plan remains unchanged.
    5.  **On Payment Success:** The service proceeds to the next step.

### 3. Updating Tenant Plan and Quota
This is the critical step that provisions the new features to the tenant after a successful payment.

-   **Action:** The service makes a synchronous, internal API call to the **Auth & Tenant Service**.
-   **Internal Endpoint:**
    -   `PUT /internal/tenants/{tenantId}/plan`
-   **Internal Request Body:**
    ```json
    {
      "newPlanId": "PRO"
    }
    ```
-   **System Reliability:** This internal call must be reliable. If the call to the Auth & Tenant Service fails after the payment has already been processed, the system must have a retry mechanism or a manual reconciliation process to ensure the tenant's plan is eventually updated. A common pattern is to use an internal message queue for this step to guarantee execution.

### 4. Billing History and Invoicing
The service maintains a record of all financial transactions for auditing and for tenants to review.

-   **API Endpoint (Public):**
    -   `GET /api/v1/payment/history`: Allows a tenant to view their past payments and invoices.
-   **Data Model (`payments` table in PostgreSQL):**
    -   `payment_id` (UUID, Primary Key)
    -   `tenant_id` (UUID, Indexed)
    -   `amount` (DECIMAL)
    -   `currency` (VARCHAR)
    -   `plan_from` (VARCHAR)
    -   `plan_to` (VARCHAR)
    -   `status` (ENUM: `SUCCESS`, `FAILED`)
    -   `provider_transaction_id` (VARCHAR): The ID from Stripe or another provider.
    -   `created_at` (TIMESTAMP)
-   This history provides a clear audit trail and can be used to generate invoices for tenants.

### 5. Mocking for Development
For development and testing purposes, the service should have a "mock" payment provider mode.

-   **Configuration:** A configuration flag (e.g., `payment.provider=MOCK`) can be used to bypass actual calls to the Stripe API.
-   **Behavior:** In mock mode, the service would accept any valid-looking `paymentMethodToken` and simulate a successful payment, allowing developers to test the full upgrade flow without using real credit cards.