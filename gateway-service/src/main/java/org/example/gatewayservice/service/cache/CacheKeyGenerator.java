package org.example.gatewayservice.service.cache;

import lombok.Data;

/** Cache key generator utility. Centralizes cache key naming convention for consistency */
@Data
public final class CacheKeyGenerator {

    private static final String AUTH_KEY_PREFIX = "auth:key:";
    private static final String QUOTA_USAGE_PREFIX = "quota:usage:";
    private static final String QUOTA_LIMIT_PREFIX = "quota:limit:";
    private static final String TENANT_INFO_PREFIX = "tenant:info:";

    /**
     * Generate cache key for API key authentication
     *
     * @param apiKey the API key
     * @return cache key
     */
    public static String authKey(String apiKey) {
        return AUTH_KEY_PREFIX + apiKey;
    }
}
