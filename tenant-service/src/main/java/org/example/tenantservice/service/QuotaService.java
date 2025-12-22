package org.example.tenantservice.service;

import java.time.Duration;

import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.TenantRepository;
import org.example.tenantservice.service.cache.CacheKeyGenerator;
import org.example.tenantservice.service.cache.CacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quota Service - Single Responsibility Principle. Handles all quota-related operations: checking,
 * incrementing, syncing with cache. Uses Redis for fast preliminary checks and PostgreSQL for
 * accurate billing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    private final TenantRepository tenantRepository;
    private final CacheService cacheService;

    private static final Duration QUOTA_CACHE_TTL = Duration.ofMinutes(10);
    private static final int UNLIMITED_QUOTA = -1;

    /**
     * Check if tenant has available quota (fast preliminary check via Redis). This is a fail-fast
     * mechanism, not for accurate billing.
     *
     * @param tenantId tenant ID
     * @return true if quota is available
     */
    public boolean hasAvailableQuota(String tenantId) {
        try {
            // Get quota usage from cache
            String usageKey = CacheKeyGenerator.quotaUsage(tenantId);
            String limitKey = CacheKeyGenerator.quotaLimit(tenantId);

            Object usageObj = cacheService.get(usageKey).orElse(null);
            Object limitObj = cacheService.get(limitKey).orElse(null);

            // If not in cache, load from database
            if (usageObj == null || limitObj == null) {
                return loadQuotaFromDatabase(tenantId);
            }

            int usage = parseIntSafely(usageObj);
            int limit = parseIntSafely(limitObj);

            // -1 means unlimited
            if (limit == UNLIMITED_QUOTA) {
                return true;
            }

            return usage < limit;

        } catch (Exception e) {
            log.error("Error checking quota for tenant {}: {}", tenantId, e.getMessage());
            // On error, allow the request (fail-open) to avoid blocking legitimate users
            return true;
        }
    }

    /**
     * Get remaining quota for tenant.
     *
     * @param tenantId tenant ID
     * @return remaining quota (-1 for unlimited)
     */
    public int getRemainingQuota(String tenantId) {
        try {
            String usageKey = CacheKeyGenerator.quotaUsage(tenantId);
            String limitKey = CacheKeyGenerator.quotaLimit(tenantId);

            Object usageObj = cacheService.get(usageKey).orElse(null);
            Object limitObj = cacheService.get(limitKey).orElse(null);

            if (usageObj == null || limitObj == null) {
                Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
                if (tenant == null) {
                    return 0;
                }
                syncQuotaToCache(tenant);
                return Math.max(0, tenant.getQuotaLimit() - tenant.getQuotaUsed());
            }

            int usage = parseIntSafely(usageObj);
            int limit = parseIntSafely(limitObj);

            if (limit == UNLIMITED_QUOTA) {
                return UNLIMITED_QUOTA;
            }

            return Math.max(0, limit - usage);

        } catch (Exception e) {
            log.error("Error getting remaining quota for tenant {}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get quota limit for tenant.
     *
     * @param tenantId tenant ID
     * @return quota limit (-1 for unlimited)
     */
    public int getQuotaLimit(String tenantId) {
        try {
            String limitKey = CacheKeyGenerator.quotaLimit(tenantId);
            Object limitObj = cacheService.get(limitKey).orElse(null);

            if (limitObj == null) {
                Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
                if (tenant == null) {
                    return 0;
                }
                syncQuotaToCache(tenant);
                return tenant.getQuotaLimit();
            }

            return parseIntSafely(limitObj);

        } catch (Exception e) {
            log.error("Error getting quota limit for tenant {}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Increment quota usage in database (accurate billing via Saga). Also syncs the new value to
     * Redis cache.
     *
     * @param tenantId tenant ID
     * @param increment increment value
     */
    @Transactional
    public void incrementQuotaUsed(String tenantId, int increment) {
        try {
            // Update database (source of truth)
            tenantRepository.incrementQuotaUsed(tenantId, increment);

            // Sync to cache
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant != null) {
                String usageKey = CacheKeyGenerator.quotaUsage(tenantId);
                cacheService.set(usageKey, tenant.getQuotaUsed());
                log.debug(
                        "Quota incremented for tenant {} by {}. New usage: {}",
                        tenantId,
                        increment,
                        tenant.getQuotaUsed());
            }

        } catch (Exception e) {
            log.error("Error incrementing quota for tenant {}: {}", tenantId, e.getMessage());
            throw e;
        }
    }

    /**
     * Sync quota information from database to cache. Called when cache misses or needs refresh.
     *
     * @param tenant tenant entity
     */
    public void syncQuotaToCache(Tenant tenant) {
        String usageKey = CacheKeyGenerator.quotaUsage(tenant.getId());
        String limitKey = CacheKeyGenerator.quotaLimit(tenant.getId());

        cacheService.set(usageKey, tenant.getQuotaUsed());
        cacheService.set(limitKey, tenant.getQuotaLimit());

        log.debug(
                "Quota synced to cache for tenant {}: usage={}, limit={}",
                tenant.getId(),
                tenant.getQuotaUsed(),
                tenant.getQuotaLimit());
    }

    /**
     * Clear quota cache for tenant. Useful when quota limit is updated.
     *
     * @param tenantId tenant ID
     */
    public void clearQuotaCache(String tenantId) {
        cacheService.delete(CacheKeyGenerator.quotaUsage(tenantId));
        cacheService.delete(CacheKeyGenerator.quotaLimit(tenantId));
        log.debug("Quota cache cleared for tenant {}", tenantId);
    }

    // Private helper methods

    private boolean loadQuotaFromDatabase(String tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("Tenant {} not found when checking quota", tenantId);
            return false;
        }

        // Sync to cache for next time
        syncQuotaToCache(tenant);

        // Check quota
        if (tenant.getQuotaLimit() == UNLIMITED_QUOTA) {
            return true;
        }

        return tenant.getQuotaUsed() < tenant.getQuotaLimit();
    }

    private int parseIntSafely(Object obj) {
        if (obj instanceof Integer) {
            return (Integer) obj;
        }
        if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);
            } catch (NumberFormatException e) {
                log.error("Failed to parse integer from string: {}", obj);
                return 0;
            }
        }
        log.warn("Unexpected type for integer parsing: {}", obj.getClass());
        return 0;
    }
}
