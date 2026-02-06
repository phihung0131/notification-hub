package org.example.notificationservice.service;

import java.time.Duration;

import org.example.notificationservice.grpc.client.TenantServiceGrpcClient;
import org.example.proto.tenant.CheckQuotaResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quota Check Service - Single Responsibility Principle. Handles quota checking with gRPC and Redis
 * caching for high performance. This is a preliminary check (fail-fast mechanism), not for accurate
 * billing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaCheckService {

    private final TenantServiceGrpcClient tenantServiceGrpcClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QUOTA_CACHE_PREFIX = "notification:quota:";
    private static final Duration QUOTA_CACHE_TTL = Duration.ofMinutes(1);

    /**
     * Check if tenant has available quota. Uses Redis cache for performance (<5ms response time).
     *
     * @param tenantId tenant ID
     * @return remaining quota count or true if allowed
     */
    public Integer hasAvailableQuota(String tenantId) {
        try {
            // Check cache first
            String cacheKey = QUOTA_CACHE_PREFIX + tenantId;
            Integer cachedResult = (Integer) redisTemplate.opsForValue().get(cacheKey);

            if (cachedResult != null) {
                log.debug("Quota check cache hit for tenant: {}", tenantId);
                return cachedResult;
            }

            // Cache miss - call gRPC
            log.debug("Quota check cache miss for tenant: {}, calling gRPC", tenantId);
            CheckQuotaResponse response = tenantServiceGrpcClient.checkQuota(tenantId);

            Integer remainingQuota = response.getRemaining();

            // Cache the result
            redisTemplate.opsForValue().set(cacheKey, remainingQuota, QUOTA_CACHE_TTL);

            log.info(
                    "Quota check for tenant {}: allowed={}, remaining={}/{}",
                    tenantId,
                    remainingQuota,
                    response.getRemaining(),
                    response.getLimit());

            return remainingQuota;

        } catch (StatusRuntimeException e) {
            log.error("gRPC error checking quota for tenant {}: {}", tenantId, e.getMessage());
            // Fail-open: allow request to prevent blocking legitimate users on service error
            return 1;

        } catch (Exception e) {
            log.error("Error checking quota for tenant {}: {}", tenantId, e.getMessage(), e);
            // Fail-open
            return 1;
        }
    }

    /**
     * Decrement quota asynchronously for tenant. Called after successful notification send.
     *
     * @param tenantId tenant ID
     */
    public void decrementQuotaAsync(String tenantId) {
        log.info("Asynchronously decrementing quota for tenant: {}", tenantId);
        String cacheKey = QUOTA_CACHE_PREFIX + tenantId;
        redisTemplate.opsForValue().decrement(cacheKey);
    }

    /**
     * Get remaining quota for tenant.
     *
     * @param tenantId tenant ID
     * @return remaining quota count
     */
    public int getRemainingQuota(String tenantId) {
        try {
            CheckQuotaResponse response = tenantServiceGrpcClient.checkQuota(tenantId);
            return response.getRemaining();
        } catch (Exception e) {
            log.error("Error getting remaining quota for tenant {}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Invalidate quota cache for tenant. Called when quota needs to be rechecked.
     *
     * @param tenantId tenant ID
     */
    public void invalidateQuotaCache(String tenantId) {
        String cacheKey = QUOTA_CACHE_PREFIX + tenantId;
        redisTemplate.delete(cacheKey);
        log.debug("Quota cache invalidated for tenant: {}", tenantId);
    }
}
