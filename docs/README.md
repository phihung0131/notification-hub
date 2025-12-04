# 📡 Notification Hub – A SaaS Multi-Channel Messaging Platform

This project is a high-performance, scalable SaaS platform designed to empower client applications (tenants) to send multi-channel notifications (Email, Telegram, etc.) through a single, unified, asynchronous API.

## 🎯 Core Objectives

- **Multi-tenancy**: The system is built to support multiple tenants on a shared infrastructure, ensuring complete data and quota isolation.
- **Scalability & Reliability**: Engineered for high throughput using a **Kafka-based asynchronous architecture**. It incorporates robust mechanisms like automated retries and Dead Letter Queues (DLQ) to guarantee message delivery.
- **Observability**: Provides end-to-end tracking for every message, allowing tenants to query the status (`PENDING`, `DELIVERED`, `FAILED`) at any time.

## 🏗️ High-Level Architecture (Core "Big Four")

The system is based on a "Core Four" microservice architecture, promoting separation of concerns, independent scaling, and resilience.

1.  **Auth & Tenant Service**: Manages tenant identities, validates API keys, and **manages usage quotas** based on subscription plans.
2.  **Notification Orchestrator**: The high-performance API entry point. It validates requests, checks preliminary quotas, generates a `messageId`, and **publishes notification jobs to Kafka**. It responds immediately (<50ms).
3.  **Delivery Service(s)**: The background worker. It consumes jobs from Kafka, communicates with third-party providers (e.g., SMTP, Telegram API), and handles retries/DLQs.
4.  **Analytics & Logging Service**: The single source of truth for message status. It consumes message lifecycle events from Kafka to provide a complete history and a query API for tenants.

![System Architecture Diagram](https://i.imgur.com/your-placeholder-diagram.png)

## 🚀 Getting Started & Development Plan

To understand the project in depth, please refer to the detailed specification documents for each service.

-   **`ARCHITECTURE.md`**: Describes the core "Big Four" architecture and data flows.
-   **`auth-tenant-service-spec.md`**: Specification for the Auth & Tenant Service (implements Saga for Quota).
-   **`notification-orchestrator-spec.md`**: Specification for the Notification Orchestrator Service.
-   **`delivery-service-spec.md`**: Specification for the Delivery Service(s).
-   **`analytics-logging-service-spec.md`**: Specification for the Analytics & Logging Service.
-   **`development-plan.md`**: A suggested 3-phase roadmap for building the core system.