# Notification Hub Documentation

Complete documentation for Notification Hub multi-tenant notification platform.

---

## 📚 Documentation Index

### Getting Started

- **[Quick Start](README_2.md#quick-start-5-minutes)** - 5-minute setup guide
- **[API Usage Guide](API_USAGE.md)** - Complete API examples với cURL & Postman
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues và solutions

---

### Architecture

- **[Architecture Overview](ARCHITECTURE.md)** - System design, patterns, data flow
- **[Architecture Diagrams](diagrams/ARCHITECTURE_DIAGRAMS.md)** - Visual documentation (8 Mermaid diagrams)

**Key Concepts:**
- [Saga Pattern](ARCHITECTURE.md#saga-pattern) - Quota management với eventual consistency
- [Strategy Pattern](diagrams/ARCHITECTURE_DIAGRAMS.md#delivery-service-strategy-pattern) - Pluggable channel adapters
- [CQRS-like Separation](ARCHITECTURE.md#cqrs) - Write (Notification) / Read (Analytics)

---

### Service Specifications

#### Core Services ("Big Five")

1. **[Gateway Service](gateway-service.md)** (Port 9000)
   - API Gateway với authentication
   - Rate limiting (Redis-based)
   - Request routing & header injection

2. **[Tenant Service](tenant-service.md)** (HTTP: 9001, gRPC: 50001)
   - Authentication (JWT) & authorization
   - Multi-tenant management
   - Quota tracking với Saga pattern
   - High-performance gRPC endpoints

3. **[Notification Service](notification-service.md)** (Port 9002)
   - Fast API entry point (<50ms)
   - Request orchestration (Facade pattern)
   - Kafka event publishing

4. **[Delivery Service](delivery-service.md)** (Port 9003)
   - Background delivery workers
   - Channel adapters (Strategy pattern)
   - Retry logic + DLQ

5. **[Analytics Service](analytics-service.md)** (Port 9004)
   - Message tracking & history
   - Out-of-order event handling
   - Query API (tenant-scoped)

---

### Development

- **[CLAUDE.md](../CLAUDE.md)** - AI-assisted development guide
- **[Environment Configuration](../.env.example)** - All environment variables
- **Build Commands** - See [README](README_2.md#step-2-build-all-services)

**Docker Compose:**
- [Kafka Infrastructure](../docker/docker-compose.kafka-dev.yml)
- [Gateway Service](../docker/docker-compose.gateway-dev.yml)
- [Tenant Service](../docker/docker-compose.tenant-dev.yml)
- [Notification Service](../docker/docker-compose.notification-dev.yml)
- [Delivery Service](../docker/docker-compose.delivery-dev.yml)
- [Analytics Service](../docker/docker-compose.analytics-dev.yml)

---

### API Reference

#### Authentication

- [Registration Flow](API_USAGE.md#step-1-register-tenant)
- [Login & JWT Tokens](API_USAGE.md#step-2-login-get-jwt-token)
- [API Key Management](API_USAGE.md#step-3-create-api-key)

#### Notifications

- [Sending Notifications](API_USAGE.md#sending-notifications)
  - Email
  - SMS
  - Telegram
- [Checking Status](API_USAGE.md#checking-message-status)

#### Error Handling

- [Error Response Format](API_USAGE.md#error-handling)
- [Error Code Ranges](API_USAGE.md#error-code-ranges)
- [Rate Limiting](API_USAGE.md#rate-limiting)

---

### Operations

#### Deployment

- **Development:** Docker Compose với watch mode
- **Staging:** Docker Compose with production configs
- **Production:** Kubernetes (manifests TBD)

#### Monitoring

**Health Checks:**
```bash
./scripts/check-health.sh
```

**Metrics (Prometheus):**
- All services expose `/actuator/prometheus`
- Grafana dashboards (TBD)

**Distributed Tracing:**
- OpenTelemetry → Jaeger
- UI: http://localhost:16686

#### Maintenance

**Utility Scripts:**
```bash
./scripts/build-all.sh        # Build all services
./scripts/start-infra.sh      # Start Kafka, DBs, Redis
./scripts/start-services.sh   # Start all microservices
./scripts/stop-all.sh         # Stop everything
./scripts/run-tests.sh        # Run test suite
./scripts/check-health.sh     # Health check all
```

---

### Technical Details

#### Kafka Topics

| Topic | Publishers | Consumers | Purpose |
|-------|------------|-----------|---------|
| `notification.requested` | Notification Service | Delivery, Analytics | New notification events |
| `notification.result` | Delivery Service | Tenant, Analytics | Delivery outcomes |
| `notification.dlq` | Delivery Service | None (manual review) | Failed messages |

**Schema Registry:** http://localhost:8081
**Avro Schema:** `commons-shared/src/main/avro/NotificationEvent.avsc`

#### gRPC Services

| Service | Port | RPCs | Callers |
|---------|------|------|---------|
| Tenant Service | 50001 | ValidateApiKey, CheckQuota | Gateway, Notification |

**Proto Definitions:** `commons-shared/src/main/proto/tenant.proto`

#### Databases

| Service | Database | Port | Tables |
|---------|----------|------|--------|
| Tenant | `tenant_db` | 5432 | tenants, api_keys, permissions |
| Notification | `notification_db` | 5433 | notifications, channels |
| Analytics | `analytics_db` | 5435 | messages |

---

### Testing

**Test Suite:**
- **Total Tests:** 96+
- **Coverage:** ~70% (critical paths: 100%)
- **Run All:** `./scripts/run-tests.sh`
- **With Coverage:** `./scripts/run-tests.sh --coverage`

**Test Types:**
- Unit Tests: Services, controllers, adapters
- Integration Tests: Kafka, gRPC (Phase 5 - skipped)
- E2E Tests: Complete flows (Phase 5 - skipped)

---

## Contributing

**Development Workflow:**
1. Read [CLAUDE.md](../CLAUDE.md) for project context
2. Create feature branch
3. Write tests first (TDD approach)
4. Implement feature
5. Ensure all tests pass: `mvn test`
6. Update documentation if needed
7. Submit pull request

**Code Standards:**
- SOLID principles
- Comprehensive Javadocs
- Unit test coverage
- Meaningful commit messages

---

## Support & Resources

**Issues:** [GitHub Issues](https://github.com/your-org/notification-hub/issues)
**Discussions:** [GitHub Discussions](https://github.com/your-org/notification-hub/discussions)

**Quick Links:**
- [Architecture Diagrams](diagrams/ARCHITECTURE_DIAGRAMS.md)
- [Troubleshooting Guide](TROUBLESHOOTING.md)
- [API Examples](API_USAGE.md)
- [Project Summary](PROJECT_COMPLETION_SUMMARY.md)

---

**Last Updated:** 2025-12-15
**Documentation Version:** 1.0
**Maintained by:** Notification Hub Team
