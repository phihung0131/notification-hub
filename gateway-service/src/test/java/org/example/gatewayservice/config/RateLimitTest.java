package org.example.gatewayservice.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitTest {

    /** Limiter that allows only the first N calls per key. */
    static class CountingLimiter extends AbstractRateLimiter<Object> {
        final Map<String, Integer> seen = new java.util.concurrent.ConcurrentHashMap<>();
        final int allowed;

        CountingLimiter(int allowed) {
            super(Object.class, "counting", null);
            this.allowed = allowed;
        }

        @Override
        public Mono<Response> isAllowed(String routeId, String id) {
            int n = seen.merge(id, 1, Integer::sum);
            return Mono.just(new Response(n <= allowed, Map.of()));
        }
    }

    private MockServerWebExchange exchangeFor(String tenant) {
        MockServerHttpRequest.BaseBuilder<?> b =
                MockServerHttpRequest.post("/api/v1/notifications/send");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        MockServerWebExchange ex = MockServerWebExchange.from(b.build());
        Route route = Route.async().id("r").uri("http://localhost").predicate(e -> true).build();
        ex.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        return ex;
    }

    @Test
    void exhaustedTenantGets429_otherTenantUnaffected() {
        KeyResolver resolver = new GatewayConfig().tenantKeyResolver();
        CountingLimiter limiter = new CountingLimiter(1);
        var gatewayFilter =
                new RequestRateLimiterGatewayFilterFactory(limiter, resolver)
                        .apply(new RequestRateLimiterGatewayFilterFactory.Config());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange first = exchangeFor("a");
        StepVerifier.create(gatewayFilter.filter(first, chain)).verifyComplete();
        assertTrue(
                first.getResponse().getStatusCode() == null
                        || first.getResponse().getStatusCode().is2xxSuccessful());

        MockServerWebExchange second = exchangeFor("a");
        StepVerifier.create(gatewayFilter.filter(second, chain)).verifyComplete();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());

        MockServerWebExchange otherTenant = exchangeFor("b");
        StepVerifier.create(gatewayFilter.filter(otherTenant, chain)).verifyComplete();
        assertTrue(
                otherTenant.getResponse().getStatusCode() == null
                        || otherTenant.getResponse().getStatusCode().is2xxSuccessful());
        assertEquals(1, limiter.seen.get("tenant:b"));
    }

    @Test
    void anonymousRequestsBucketedByAddressNotShared() {
        KeyResolver resolver = new GatewayConfig().tenantKeyResolver();
        MockServerWebExchange ex =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/x")
                                .remoteAddress(new InetSocketAddress("10.1.2.3", 1234))
                                .build());
        assertEquals("anon:10.1.2.3", resolver.resolve(ex).block());
    }

    private GatewayConfig.MeteredRedisRateLimiter limiter(SimpleMeterRegistry reg) {
        return new GatewayConfig.MeteredRedisRateLimiter(
                null, null, null, reg.counter("gateway.ratelimit.failopen"), Duration.ofMillis(50));
    }

    @Test
    void redisErrorFailsOpenAndIsCounted() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var l = limiter(reg);
        StepVerifier.create(l.decorate(Mono.error(new IllegalStateException("redis down"))))
                .expectNextMatches(RateLimiter.Response::isAllowed)
                .verifyComplete();
        assertEquals(1.0, reg.counter("gateway.ratelimit.failopen").count());
    }

    @Test
    void redisTimeoutFailsOpenAndIsCounted() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var l = limiter(reg);
        StepVerifier.create(l.decorate(Mono.never()))
                .expectNextMatches(RateLimiter.Response::isAllowed)
                .verifyComplete();
        assertEquals(1.0, reg.counter("gateway.ratelimit.failopen").count());
    }

    @Test
    void swallowedRedisErrorSignalledByRemainingMinusOneIsCounted() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var l = limiter(reg);
        var failOpen = new RateLimiter.Response(true, Map.of(l.getRemainingHeader(), "-1"));
        StepVerifier.create(l.decorate(Mono.just(failOpen))).expectNextCount(1).verifyComplete();
        assertEquals(1.0, reg.counter("gateway.ratelimit.failopen").count());
    }

    @Test
    void normalAllowedAndDeniedNotCountedAsFailOpen() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var l = limiter(reg);
        StepVerifier.create(
                        l.decorate(
                                Mono.just(
                                        new RateLimiter.Response(
                                                false, Map.of(l.getRemainingHeader(), "0")))))
                .expectNextMatches(r -> !r.isAllowed())
                .verifyComplete();
        assertEquals(0.0, reg.counter("gateway.ratelimit.failopen").count());
    }
}
