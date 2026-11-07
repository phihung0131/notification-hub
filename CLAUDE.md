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

Root POM only includes `commons-shared` and `gateway-service` by default. Other services are activated via Maven profiles: `tenant`, `notification`, `delivery`, `analytics`, `gateway`.

```bash
./scripts/build-all.sh            # format + install commons-shared + build all services (-DskipTests)
mvn clean install -pl commons-shared   # must be rebuilt after changing .proto / .avsc
./scripts/format-code.sh          # Spotless apply (Google Java Format, AOSP style)
./scripts/format-code.sh check    # Spotless check only
mvn test -Ptenant,notification,delivery,analytics,gateway       # all tests
mvn test -Pdelivery -pl delivery-service -Dtest=DeliveryProcessorTest            # single class
mvn test -Pdelivery -pl delivery-service -Dtest=DeliveryProcessorTest#methodName # single method
./scripts/start-services.sh       # docker network + Kafka/monitoring infra + all services via compose `up --watch`
./scripts/stop-all.sh [--clean]   # --clean removes volumes (data loss)
./scripts/check-health.sh         # curl actuator health of every service
./scripts/test.sh                 # end-to-end smoke + ab benchmark; needs the full stack running
```

Notes:
- Compose files are in `docker/` (`docker-compose.<svc>-dev.yml`, `kafka-dev`, `monitoring-dev`); external Docker network is `notification-net`.
- Observability: OpenTelemetry java agent in `agents/`, collector config in `config/`, Jaeger at :16686, Kafka UI at :8080, Schema Registry at :8081.
- Formatting is enforced by Spotless: run `format-code.sh` before committing; import order java → javax → jakarta → org → com, unused imports removed.
- Actuator paths include the service context path, e.g. `http://localhost:9001/api/v1/tenants/actuator/health`.
- Tests are sparse unit tests (Mockito-style) under each service's `src/test`.

## Architecture (big picture)

Request path: client → **gateway** (validates `X-Api-Key` by calling tenant-service, rate limits via Redis) → **notification-service** `/api/v1/notifications/send` → publishes `NotificationRequested` Avro event to Kafka → consumed independently by **delivery-service** (sends via channel adapter) and **analytics-service** (records history). Delivery publishes a result event, consumed by **analytics-service** and **tenant-service**.

Key points requiring multi-file context:
- **Async, event-driven core.** Topics are configured via `app.kafka.topic.requested` and `app.kafka.topic.result`. Event schema is shared in `commons-shared` (Avro); changing it affects all four producers/consumers.
- **Quota via Saga / eventual consistency.** tenant-service reserves quota on the sync path (gRPC, high performance) and reconciles it from the result event (`tenant-service/.../NotificationResultConsumer`).
- **CQRS-like split.** Writes in notification-service, reads in analytics-service; analytics must tolerate out-of-order events (result may arrive before requested).
- **Delivery uses Strategy pattern.** Adapters are looked up through `DeliveryAdapterRegistry`; add a channel by adding an adapter, not by editing the processor. Failures go through retry then DLQ.
- **Synchronous inter-service calls** use gRPC defined in `commons-shared/src/main/proto/tenant.proto` (e.g. gateway/notification → tenant).
- Each service has its own database/config; env vars are prefixed per service (`TENANT_DB_URL`, `GATEWAY_REDIS_HOST`, …) in `.env.example`.
