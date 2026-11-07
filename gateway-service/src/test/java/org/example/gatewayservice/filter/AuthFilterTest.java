package org.example.gatewayservice.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.example.gatewayservice.config.GatewayConfig;
import org.example.gatewayservice.exception.InvalidCredentialsException;
import org.example.gatewayservice.exception.TenantServiceUnavailableException;
import org.example.gatewayservice.model.ApiKeyValidationResponse;
import org.example.gatewayservice.service.TenantValidationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthFilterTest {

    private TenantValidationClient client;
    private GatewayFilterChain chain;
    private AuthFilter filter;
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        client = mock(TenantValidationClient.class);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any()))
                .thenAnswer(
                        inv -> {
                            forwarded.set(inv.getArgument(0));
                            return Mono.empty();
                        });
        AuthFilter.AuthConfig cfg = new AuthFilter.AuthConfig();
        AuthFilter.AuthConfig.RouteAuth apiKeyRoute = new AuthFilter.AuthConfig.RouteAuth();
        apiKeyRoute.setPattern("/api/v1/notifications/**");
        apiKeyRoute.setMethod("API_KEY");
        cfg.setRoutes(List.of(apiKeyRoute));
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        filter = new AuthFilter(client, cfg, mapper);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> b) {
        return MockServerWebExchange.from(b.build());
    }

    @Test
    void whitelistedPathSkipsAuth() {
        MockServerWebExchange ex = exchange(MockServerHttpRequest.get("/actuator/health"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        verify(client, never()).validateApiKey(anyString());
        assertEquals(ex.getRequest().getURI(), forwarded.get().getRequest().getURI());
    }

    @Test
    void missingApiKeyIs401() {
        MockServerWebExchange ex =
                exchange(MockServerHttpRequest.post("/api/v1/notifications/send"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void invalidApiKeyIs401() {
        when(client.validateApiKey("bad"))
                .thenReturn(Mono.error(new InvalidCredentialsException("Invalid API key")));
        MockServerWebExchange ex =
                exchange(
                        MockServerHttpRequest.post("/api/v1/notifications/send")
                                .header("X-Api-Key", "bad"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void tenantServiceDownIs503NotFound401() {
        when(client.validateApiKey("k"))
                .thenReturn(Mono.error(new TenantServiceUnavailableException("down", null)));
        MockServerWebExchange ex =
                exchange(
                        MockServerHttpRequest.post("/api/v1/notifications/send")
                                .header("X-Api-Key", "k"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getResponse().getStatusCode());
        assertEquals("1", ex.getResponse().getHeaders().getFirst("Retry-After"));
        verify(chain, never()).filter(any());
    }

    @Test
    void validKeyInjectsTenantAndPassesOtherHeadersUntouched() {
        when(client.validateApiKey("good"))
                .thenReturn(Mono.just(new ApiKeyValidationResponse("t-1", Set.of("SEND"))));
        MockServerWebExchange ex =
                exchange(
                        MockServerHttpRequest.post("/api/v1/notifications/send")
                                .header("X-Api-Key", "good")
                                .header("Idempotency-Key", "abc-123")
                                .header("X-Request-Id", "r-9"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        var h = forwarded.get().getRequest().getHeaders();
        assertEquals("t-1", h.getFirst("X-Tenant-Id"));
        assertEquals("SEND", h.getFirst("X-Permissions"));
        assertEquals("abc-123", h.getFirst("Idempotency-Key"));
        assertEquals("r-9", h.getFirst("X-Request-Id"));
        assertEquals("good", h.getFirst("X-Api-Key"));
    }

    @Test
    void spoofedTenantHeaderIsOverwrittenAndRateLimitKeyUsesAuthenticatedTenant() {
        when(client.validateApiKey("good"))
                .thenReturn(Mono.just(new ApiKeyValidationResponse("real-tenant", Set.of())));
        MockServerWebExchange ex =
                exchange(
                        MockServerHttpRequest.post("/api/v1/notifications/send")
                                .header("X-Api-Key", "good")
                                .header("X-Tenant-Id", "victim"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        String key = new GatewayConfig().tenantKeyResolver().resolve(forwarded.get()).block();
        assertEquals("tenant:real-tenant", key);
    }

    @Test
    void spoofedTenantHeaderStrippedOnWhitelistedPath() {
        MockServerWebExchange ex =
                exchange(
                        MockServerHttpRequest.post("/api/v1/tenants/auth/login")
                                .header("X-Tenant-Id", "victim"));
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        assertNull(forwarded.get().getRequest().getHeaders().getFirst("X-Tenant-Id"));
    }

    @Test
    void runsBeforeRateLimiter() {
        // default-filters (RequestRateLimiter) are ordered from 1 upward
        assertTrue(filter.getOrder() < 1);
    }
}
