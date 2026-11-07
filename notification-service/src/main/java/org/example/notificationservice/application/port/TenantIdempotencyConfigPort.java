package org.example.notificationservice.application.port;

import java.time.Duration;

import org.example.notificationservice.domain.IdempotencyMode;

public interface TenantIdempotencyConfigPort {

    record Config(IdempotencyMode mode, Duration hashWindow) {}

    Config forTenant(String tenantId);
}
