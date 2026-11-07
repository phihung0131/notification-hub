package org.example.notificationservice.adapters.jdbc;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.example.notificationservice.application.port.TenantIdempotencyConfigPort;
import org.example.notificationservice.domain.IdempotencyMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-tenant idempotency config from table {@code tenant_idempotency_config} (30s in-memory cache).
 * Tenants without a row get the global default (HEADER_ONLY: header-first, no hash fallback).
 */
@Component
public class TenantIdempotencyConfigAdapter implements TenantIdempotencyConfigPort {

    private record Cached(Config config, long loadedAtMs) {}

    private static final long CACHE_MS = 30_000;

    private final JdbcTemplate jdbc;
    private final Config defaults;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantIdempotencyConfigAdapter(
            JdbcTemplate jdbc,
            @Value("${notification.idempotency.default-mode:HEADER_ONLY}") IdempotencyMode mode,
            @Value("${notification.idempotency.hash-window:PT60S}") Duration window) {
        this.jdbc = jdbc;
        this.defaults = new Config(mode, window);
    }

    @Override
    public Config forTenant(String tenantId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(tenantId);
        if (c != null && now - c.loadedAtMs() < CACHE_MS) {
            return c.config();
        }
        Config loaded;
        try {
            loaded =
                    jdbc
                            .query(
                                    "SELECT mode, hash_window_seconds FROM tenant_idempotency_config"
                                            + " WHERE tenant_id = ?",
                                    (rs, i) ->
                                            new Config(
                                                    IdempotencyMode.valueOf(rs.getString(1)),
                                                    Duration.ofSeconds(rs.getInt(2))),
                                    tenantId)
                            .stream()
                            .findFirst()
                            .orElse(defaults);
        } catch (RuntimeException e) {
            return c != null ? c.config() : defaults;
        }
        cache.put(tenantId, new Cached(loaded, now));
        return loaded;
    }
}
