package org.example.gatewayservice.service.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generic Redis cache service following KISS principle. Provides simple get/set/delete operations
 * with TTL support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Get value from cache
     *
     * @param key cache key
     * @return Optional containing the value if present
     */
    public Optional<Object> get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.error("Error getting key {} from cache: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Set value in cache with TTL
     *
     * @param key cache key
     * @param value value to cache
     * @param ttl time to live
     */
    public void set(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.error("Error setting key {} in cache: {}", key, e.getMessage());
        }
    }

    /**
     * Set value in cache without expiration
     *
     * @param key cache key
     * @param value value to cache
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("Error setting key {} in cache: {}", key, e.getMessage());
        }
    }

    /**
     * Delete value from cache
     *
     * @param key cache key
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error deleting key {} from cache: {}", key, e.getMessage());
        }
    }

    /**
     * Increment counter in cache
     *
     * @param key cache key
     * @return new value after increment
     */
    public Long increment(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.error("Error incrementing key {} in cache: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Increment counter by delta
     *
     * @param key cache key
     * @param delta increment value
     * @return new value after increment
     */
    public Long incrementBy(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("Error incrementing key {} by {} in cache: {}", key, delta, e.getMessage());
            return null;
        }
    }

    /**
     * Check if key exists in cache
     *
     * @param key cache key
     * @return true if exists
     */
    public boolean exists(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("Error checking existence of key {} in cache: {}", key, e.getMessage());
            return false;
        }
    }
}
