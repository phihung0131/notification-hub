package org.example.tenantservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

import org.example.tenantservice.common.enums.TenantStatus;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.service.cache.CacheKeyGenerator;
import org.example.tenantservice.service.cache.CacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * API Key Validation Service - Single Responsibility Principle. Handles API key validation with
 * Redis caching for high performance. Used by Gateway via gRPC for fast authentication.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyValidationService {

    private final ApiKeyRepository apiKeyRepository;
    private final CacheService cacheService;

    private static final Duration AUTH_CACHE_TTL = Duration.ofMinutes(30);

    /**
     * Validate API key and return tenant information. Uses cache-aside pattern for optimal
     * performance.
     *
     * @param rawApiKey the raw API key
     * @return TenantInfo if valid, null if invalid
     */
    @Transactional(readOnly = true)
    public TenantInfo validateApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.trim().isEmpty()) {
            log.warn("Empty API key validation attempt");
            return null;
        }

        try {
            // Check cache first
            String cacheKey = CacheKeyGenerator.authKey(rawApiKey);
            Object cached = cacheService.get(cacheKey).orElse(null);

            if (cached instanceof TenantInfo) {
                log.debug("API key validation cache hit for key: {}...", maskApiKey(rawApiKey));
                return (TenantInfo) cached;
            }

            // Cache miss - load from database
            ApiKey apiKey = apiKeyRepository.findByKey(rawApiKey).orElse(null);

            if (apiKey == null) {
                log.warn("Invalid API key: {}...", maskApiKey(rawApiKey));
                return null;
            }

            // Check if API key is revoked
            if (apiKey.isRevoked()) {
                log.warn("Revoked API key used: {}...", maskApiKey(rawApiKey));
                return null;
            }

            // Check if API key is expired
            if (apiKey.getExpiredAt() != null && Instant.now().isAfter(apiKey.getExpiredAt())) {
                log.warn("Expired API key used: {}...", maskApiKey(rawApiKey));
                return null;
            }

            // Get tenant information
            Tenant tenant = apiKey.getTenant();
            if (tenant == null) {
                log.error("API key {} has no associated tenant", maskApiKey(rawApiKey));
                return null;
            }

            // Build tenant info
            TenantInfo tenantInfo =
                    TenantInfo.builder()
                            .tenantId(tenant.getId())
                            .status(tenant.getStatus().name())
                            .plan(tenant.getPlan())
                            .quotaLimit(tenant.getQuotaLimit())
                            .quotaUsed(tenant.getQuotaUsed())
                            .permissions(
                                    apiKey.getPermissions().stream()
                                            .map(Permission::getName)
                                            .collect(Collectors.toList()))
                            .build();

            // Cache the result
            cacheService.set(cacheKey, tenantInfo, AUTH_CACHE_TTL);

            log.debug("API key validated successfully for tenant: {}", tenant.getId());
            return tenantInfo;

        } catch (Exception e) {
            log.error("Error validating API key: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check if API key is valid (boolean response).
     *
     * @param rawApiKey the raw API key
     * @return true if valid and active
     */
    public boolean isValidApiKey(String rawApiKey) {
        TenantInfo info = validateApiKey(rawApiKey);
        return info != null && TenantStatus.ACTIVE.name().equals(info.getStatus());
    }

    /**
     * Invalidate API key cache. Called when API key is revoked or tenant status changes.
     *
     * @param rawApiKey the raw API key
     */
    public void invalidateCache(String rawApiKey) {
        String cacheKey = CacheKeyGenerator.authKey(rawApiKey);
        cacheService.delete(cacheKey);
        log.debug("API key cache invalidated for: {}...", maskApiKey(rawApiKey));
    }

    /**
     * Mask API key for logging (show only first 8 characters).
     *
     * @param apiKey the API key
     * @return masked API key
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 8) + "***";
    }
}
