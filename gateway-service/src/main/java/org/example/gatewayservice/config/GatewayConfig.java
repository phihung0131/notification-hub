package org.example.gatewayservice.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    /**
     * Rate-limit bucket per tenant. X-Tenant-Id is trustworthy here because AuthFilter strips any
     * client-supplied value and runs before RequestRateLimiter (see AuthFilter#ORDER). Requests
     * with no tenant (whitelisted paths such as login/register) are bucketed per client address, so
     * anonymous traffic cannot share, or exhaust, a tenant's bucket.
     */
    @Bean
    public KeyResolver tenantKeyResolver() {
        return (ServerWebExchange exchange) -> {
            String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
            if (tenant != null && !tenant.isBlank()) {
                return Mono.just("tenant:" + tenant);
            }
            var remote = exchange.getRequest().getRemoteAddress();
            String ip =
                    remote == null || remote.getAddress() == null
                            ? "unknown"
                            : remote.getAddress().getHostAddress();
            return Mono.just("anon:" + ip);
        };
    }

    /**
     * Redis rate limiter with an explicit fail policy: <b>fail-open</b>. If Redis errors or does
     * not answer within {@code app.rate-limit.redis-timeout}, the request is allowed and {@code
     * gateway.ratelimit.failopen} is incremented. Availability of the API beats strict limiting;
     * quota enforcement downstream remains exact.
     */
    @Bean
    @Primary
    public MeteredRedisRateLimiter meteredRedisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            MeterRegistry registry,
            @Value("${app.rate-limit.redis-timeout:200ms}") Duration redisTimeout) {
        return new MeteredRedisRateLimiter(
                redisTemplate,
                script,
                configurationService,
                registry.counter("gateway.ratelimit.failopen"),
                redisTimeout);
    }

    /** Package-visible for tests. */
    public static class MeteredRedisRateLimiter extends RedisRateLimiter {

        private final Counter failOpen;
        private final Duration timeout;

        public MeteredRedisRateLimiter(
                ReactiveStringRedisTemplate redisTemplate,
                RedisScript<List<Long>> script,
                ConfigurationService configurationService,
                Counter failOpen,
                Duration timeout) {
            super(redisTemplate, script, configurationService);
            this.failOpen = failOpen;
            this.timeout = timeout;
        }

        @Override
        public Mono<Response> isAllowed(String routeId, String id) {
            return decorate(super.isAllowed(routeId, id));
        }

        /** Applies timeout + fail-open accounting to a delegate result. */
        Mono<Response> decorate(Mono<Response> delegate) {
            return delegate.timeout(timeout)
                    .onErrorResume(
                            e -> {
                                failOpen.increment();
                                return Mono.just(new Response(true, Map.of()));
                            })
                    .doOnNext(
                            r -> {
                                // RedisRateLimiter swallows Redis errors and reports remaining=-1
                                if ("-1".equals(r.getHeaders().get(getRemainingHeader()))) {
                                    failOpen.increment();
                                }
                            });
        }
    }
}
