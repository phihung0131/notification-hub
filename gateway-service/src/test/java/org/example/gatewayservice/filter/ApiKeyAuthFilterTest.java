package org.example.gatewayservice.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Set;

import org.example.gatewayservice.model.ApiKeyValidationResponse;
import org.example.gatewayservice.service.TenantValidationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyAuthFilterTest {

    @Mock private TenantValidationClient tenantValidationClient;

    @Mock private GatewayFilterChain chain;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(tenantValidationClient);
        // Mock chain to return completed mono
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should allow request to whitelisted actuator path without authentication")
    void filter_ActuatorPath_AllowsWithoutAuth() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain).filter(exchange);
        verify(tenantValidationClient, never()).validateApiKey(anyString());
        // Status is OK or null (not set) for successful whitelisted paths
    }

    @Test
    @DisplayName("Should allow request to whitelisted register path without authentication")
    void filter_RegisterPath_AllowsWithoutAuth() {
        // Given
        MockServerHttpRequest request =
                MockServerHttpRequest.post("/api/v1/tenants/auth/register").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain).filter(exchange);
        verify(tenantValidationClient, never()).validateApiKey(anyString());
    }

    @Test
    @DisplayName("Should allow request to whitelisted login path without authentication")
    void filter_LoginPath_AllowsWithoutAuth() {
        // Given
        MockServerHttpRequest request =
                MockServerHttpRequest.post("/api/v1/tenants/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain).filter(exchange);
        verify(tenantValidationClient, never()).validateApiKey(anyString());
    }

    @Test
    @DisplayName("Should return 401 when Authorization header is missing")
    void filter_MissingAuthHeader_Returns401() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/notifications").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain, never()).filter(any());
        verify(tenantValidationClient, never()).validateApiKey(anyString());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(
                "Missing Authorization Bearer token",
                exchange.getResponse().getHeaders().getFirst("X-Error"));
    }

    @Test
    @DisplayName("Should return 401 when Authorization header does not start with Bearer")
    void filter_InvalidAuthHeaderFormat_Returns401() {
        // Given
        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Basic invalid-format")
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain, never()).filter(any());
        verify(tenantValidationClient, never()).validateApiKey(anyString());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(
                "Missing Authorization Bearer token",
                exchange.getResponse().getHeaders().getFirst("X-Error"));
    }

    @Test
    @DisplayName("Should validate API key and inject tenant headers on successful authentication")
    void filter_ValidApiKey_InjectsTenantHeaders() {
        // Given
        String apiKey = "valid-api-key-12345";
        String tenantId = "tenant-123";
        Set<String> permissions = Set.of("SEND_NOTIFICATION", "VIEW_ANALYTICS");

        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ApiKeyValidationResponse validationResponse =
                new ApiKeyValidationResponse(tenantId, permissions);

        when(tenantValidationClient.validateApiKey(apiKey))
                .thenReturn(Mono.just(validationResponse));

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(tenantValidationClient).validateApiKey(apiKey);
        verify(chain)
                .filter(
                        argThat(
                                ex -> {
                                    String injectedTenantId =
                                            ex.getRequest().getHeaders().getFirst("X-Tenant-Id");
                                    String injectedPermissions =
                                            ex.getRequest().getHeaders().getFirst("X-Permissions");

                                    assertNotNull(injectedTenantId);
                                    assertNotNull(injectedPermissions);
                                    assertEquals(tenantId, injectedTenantId);
                                    // Set does not guarantee order, check both permissions are
                                    // present
                                    assertTrue(injectedPermissions.contains("SEND_NOTIFICATION"));
                                    assertTrue(injectedPermissions.contains("VIEW_ANALYTICS"));

                                    return true;
                                }));
    }

    @Test
    @DisplayName("Should return 401 when API key validation fails")
    void filter_InvalidApiKey_Returns401() {
        // Given
        String apiKey = "invalid-api-key";

        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(tenantValidationClient.validateApiKey(apiKey))
                .thenReturn(Mono.error(new RuntimeException("Invalid API key")));

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(tenantValidationClient).validateApiKey(apiKey);
        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("Invalid API key", exchange.getResponse().getHeaders().getFirst("X-Error"));
    }

    @Test
    @DisplayName("Should return 401 when tenant validation client throws exception")
    void filter_ValidationClientError_Returns401() {
        // Given
        String apiKey = "some-api-key";

        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(tenantValidationClient.validateApiKey(apiKey))
                .thenReturn(Mono.error(new RuntimeException("Tenant service unavailable")));

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(tenantValidationClient).validateApiKey(apiKey);
        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Should extract API key correctly from Bearer token")
    void filter_BearerToken_ExtractsApiKeyCorrectly() {
        // Given
        String apiKey = "extracted-key-xyz";
        String tenantId = "tenant-456";

        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ApiKeyValidationResponse validationResponse =
                new ApiKeyValidationResponse(tenantId, Set.of("READ"));

        when(tenantValidationClient.validateApiKey(apiKey))
                .thenReturn(Mono.just(validationResponse));

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(tenantValidationClient).validateApiKey(apiKey);
    }

    @Test
    @DisplayName("Should have correct filter order")
    void getOrder_ReturnsNegativeOne() {
        // When
        int order = filter.getOrder();

        // Then
        assertEquals(-1, order);
    }

    @Test
    @DisplayName("Should match actuator paths with wildcards")
    void filter_ActuatorWildcard_Matches() {
        // Given - различные actuator paths
        String[] actuatorPaths = {
            "/actuator/health", "/actuator/metrics", "/actuator/prometheus", "/actuator/info"
        };

        for (String path : actuatorPaths) {
            MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result).verifyComplete();

            verify(chain, atLeastOnce()).filter(any());
        }

        verify(tenantValidationClient, never()).validateApiKey(anyString());
    }

    @Test
    @DisplayName("Should inject empty permissions when tenant has no permissions")
    void filter_NoPermissions_InjectsEmptyString() {
        // Given
        String apiKey = "api-key-no-perms";
        String tenantId = "tenant-789";

        MockServerHttpRequest request =
                MockServerHttpRequest.get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ApiKeyValidationResponse validationResponse =
                new ApiKeyValidationResponse(
                        tenantId, Set.of() // empty permissions
                        );

        when(tenantValidationClient.validateApiKey(apiKey))
                .thenReturn(Mono.just(validationResponse));

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();

        verify(chain)
                .filter(
                        argThat(
                                ex -> {
                                    String injectedPermissions =
                                            ex.getRequest().getHeaders().getFirst("X-Permissions");
                                    assertNotNull(injectedPermissions);
                                    assertEquals("", injectedPermissions);
                                    return true;
                                }));
    }
}
