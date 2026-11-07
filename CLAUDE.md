# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Multi-tenant notification platform. Java 21, Spring Boot microservices, Maven multi-module (root `pom.xml`), Kafka (Avro + Schema Registry), gRPC, PostgreSQL, Redis. Package root: `org.example.<service>`. Docs live in `docs/` (per-service specs, `API_USAGE.md`, diagrams); env vars template in `.env.example`.

## Modules and ports

| Module | Port | Role |
|---|---|---|
| `commons-shared` | – | Shared lib: `tenant.proto` (gRPC), `NotificationEvent.avsc` (Avro), base classes, exceptions, utils |
| `gateway-service` | 9000 | API key auth, Redis rate limiting, routing, header injection |
| `tenant-service` | 9001 (gRPC 50001) | JWT auth, tenants, permissions, API keys, quota (Saga) |
| `notification-service` | 9002 | Fast ingest API, publishes Kafka events |
| `delivery-service` | 9003 | Kafka workers, channel adapters (Strategy), retry + DLQ |
| `analytics-service` | 9004 | Message tracking/history, tenant-scoped query API |

## Commands

Only Docker + Compose are needed on the host (builds run in a multi-stage `Dockerfile`; one image recipe for all services via `SERVICE`/`PROFILE` build args).

```bash
make up        # build + start core stack (kafka, schema-registry, postgres, 2x redis, mailpit, 5 services)
make obs       # same + Prometheus/Grafana/Jaeger + OTel agent
make smoke     # register tenant -> API key -> channel -> POST /send through gateway
make health    # curl actuator health of every service
make fmt       # Spotless (Google Java Format, AOSP) inside a container
make down      # stop (keep data) | make clean = stop + delete volumes
docker compose --profile tools up -d   # + Kafka UI (:8080)
```

Maven profiles (`tenant`, `notification`, `delivery`, `analytics`, `gateway`) select service modules; root POM always includes `commons-shared`. Rebuild images after changing `.proto`/`.avsc`. Single test (needs host JDK 21, or run inside a `maven:3.9.9-eclipse-temurin-21` container):
`mvn test -Pdelivery -pl delivery-service -Dtest=DeliveryProcessorTest#method`.

Notes:
- Topics are created by the `kafka-init` job (auto-create disabled); partition count from `TOPIC_PARTITIONS`.
- One Postgres container holds a database + role per service (`docker/postgres/init.sh`); `redis-edge` = gateway rate limit, `redis-core` = quota/caches.
- Container memory limits are set in `compose.yaml` for a ~16GB laptop.
- Actuator paths include the service context path, e.g. `http://localhost:9001/api/v1/tenants/actuator/health`.
- Mail sink UI: Mailpit at http://localhost:8025.

## Architecture (big picture)

Request path: client → **gateway** (validates `X-Api-Key` by calling tenant-service, rate limits via Redis) → **notification-service** `/api/v1/notifications/send` → publishes `NotificationRequested` Avro event to Kafka → consumed independently by **delivery-service** (sends via channel adapter) and **analytics-service** (records history). Delivery publishes a result event, consumed by **analytics-service** and **tenant-service**.

Key points requiring multi-file context:
- **Async, event-driven core.** Topics are configured via `app.kafka.topic.requested` and `app.kafka.topic.result`. Event schema is shared in `commons-shared` (Avro); changing it affects all four producers/consumers.
- **Quota via Saga / eventual consistency.** tenant-service reserves quota on the sync path (gRPC, high performance) and reconciles it from the result event (`tenant-service/.../NotificationResultConsumer`).
- **CQRS-like split.** Writes in notification-service, reads in analytics-service; analytics must tolerate out-of-order events (result may arrive before requested).
- **Delivery uses Strategy pattern.** Adapters are looked up through `DeliveryAdapterRegistry`; add a channel by adding an adapter, not by editing the processor. Failures go through retry then DLQ.
- **Synchronous inter-service calls** use gRPC defined in `commons-shared/src/main/proto/tenant.proto` (e.g. gateway/notification → tenant).
- Each service has its own database/config; env vars are prefixed per service (`TENANT_DB_URL`, `GATEWAY_REDIS_HOST`, …) in `.env.example`.
