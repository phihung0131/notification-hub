package org.example.gatewayservice.filter;

import java.util.List;

import org.example.commons.baseclass.ApiResponse;
import org.example.commons.exception.ApiError;
import org.example.gatewayservice.common.exception.ApiErrorMessage;
import org.example.gatewayservice.service.TenantValidationClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Global filter for API key and JWT authentication with tenant context injection. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthFilter implements GlobalFilter, Ordered {

    private final TenantValidationClient tenantValidationClient;
    private final AuthConfig authConfig;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> WHITELIST =
            List.of("/actuator/**", "/api/v1/tenants/auth/register", "/api/v1/tenants/auth/login");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // Determine auth method for this path
        AuthMethod authMethod = getAuthMethodForPath(path);

        return switch (authMethod) {
            case API_KEY -> handleApiKeyAuth(exchange, chain);
            case JWT -> handleJwtAuth(exchange, chain);
            case BOTH -> handleBothAuth(exchange, chain);
            case NONE -> chain.filter(exchange);
        };
    }

    private Mono<Void> handleApiKeyAuth(ServerWebExchange exchange, GatewayFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");

        if (apiKey == null || apiKey.isBlank()) {
            return unauthorized(exchange, "Missing X-Api-Key header");
        }

        return tenantValidationClient
                .validateApiKey(apiKey)
                .flatMap(
                        resp -> {
                            ServerHttpRequest mutated =
                                    exchange.getRequest()
                                            .mutate()
                                            .header("X-Tenant-Id", resp.tenantId())
                                            .header(
                                                    "X-Permissions",
                                                    String.join(",", resp.permissions()))
                                            .build();
                            return chain.filter(exchange.mutate().request(mutated).build());
                        })
                .onErrorResume(
                        ex -> {
                            log.warn("API key validation failed: {}", ex.getMessage());
                            return unauthorized(exchange, "Invalid API key");
                        });
    }

    private Mono<Void> handleJwtAuth(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing Authorization Bearer token");
        }

        String token = authHeader.substring("Bearer ".length());

        return tenantValidationClient
                .validateJwt(token)
                .flatMap(
                        resp -> {
                            ServerHttpRequest mutated =
                                    exchange.getRequest()
                                            .mutate()
                                            .header("X-Tenant-Id", resp.tenantId())
                                            .header(
                                                    "X-Permissions",
                                                    String.join(",", resp.permissions()))
                                            .header("X-Auth-Method", "JWT")
                                            .build();
                            return chain.filter(exchange.mutate().request(mutated).build());
                        })
                .onErrorResume(
                        ex -> {
                            log.warn("JWT validation failed: {}", ex.getMessage());
                            return unauthorized(exchange, "Invalid JWT token");
                        });
    }

    private Mono<Void> handleBothAuth(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Try JWT first
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return handleJwtAuth(exchange, chain);
        }

        // Fallback to API Key
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return handleApiKeyAuth(exchange, chain);
        }

        return unauthorized(
                exchange, "Missing authentication: provide either Bearer token or X-Api-Key");
    }

    private AuthMethod getAuthMethodForPath(String path) {
        log.debug("Checking auth method for path: {}", path);

        // Check route-specific auth config
        for (AuthConfig.RouteAuth route : authConfig.getRoutes()) {
            log.debug("Matching pattern '{}' against path '{}'", route.getPattern(), path);
            if (pathMatcher.match(route.getPattern(), path)) {
                log.debug("Matched! Using auth method: {}", route.getMethod());
                return AuthMethod.valueOf(route.getMethod().toUpperCase());
            }
        }

        // Return default auth method
        log.debug("No pattern matched, using default: {}", authConfig.getDefaultMethod());
        return AuthMethod.valueOf(authConfig.getDefaultMethod().toUpperCase());
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ApiResponse<Object> apiResponse =
                ApiResponse.fail(
                        ApiError.builder()
                                .code(ApiErrorMessage.AUTHENTICATE_VALIDATION_FAILED.getCode())
                                .message(message)
                                .details("Jwt token or API key is invalid or missing")
                                .build());

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse()
                .getHeaders()
                .setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(apiResponse);
            org.springframework.core.io.buffer.DataBuffer buffer =
                    exchange.getResponse().bufferFactory().wrap(bytes);

            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Error serializing error response", e);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -1; // run early
    }

    enum AuthMethod {
        API_KEY,
        JWT,
        BOTH,
        NONE
    }

    @Component
    @ConfigurationProperties(prefix = "app.auth")
    @Data
    static class AuthConfig {
        private String defaultMethod = "JWT";
        private List<RouteAuth> routes = List.of();

        @Data
        public static class RouteAuth {
            private String pattern;
            private String method;
        }

        // Helper method để get auth method by pattern
        public String getMethodForPattern(String pattern) {
            return routes.stream()
                    .filter(route -> route.getPattern().equals(pattern))
                    .map(RouteAuth::getMethod)
                    .findFirst()
                    .orElse(null);
        }
    }
}
