package org.example.tenantservice.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * General cache configuration use for all caches unless overridden by specific cache
     * configurations.
     */
    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeValuesWith(
                        SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
    }

    /** Specific cache configurations for different caches with custom TTLs. */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) ->
                builder.withCacheConfiguration(
                                "permissions",
                                RedisCacheConfiguration.defaultCacheConfig()
                                        .entryTtl(Duration.ofDays(1))
                                        .serializeValuesWith(
                                                SerializationPair.fromSerializer(
                                                        new GenericJackson2JsonRedisSerializer())))
                        .withCacheConfiguration(
                                "tenantDetailsByEmail",
                                RedisCacheConfiguration.defaultCacheConfig()
                                        .entryTtl(Duration.ofMinutes(30))
                                        .serializeValuesWith(
                                                SerializationPair.fromSerializer(
                                                        new GenericJackson2JsonRedisSerializer())));
    }
}
