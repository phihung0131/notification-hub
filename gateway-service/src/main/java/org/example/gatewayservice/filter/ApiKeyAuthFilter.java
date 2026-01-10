package org.example.gatewayservice.filter;

import java.util.List;

import org.example.gatewayservice.service.TenantValidationClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Global filter for API key authentication and tenant context injection. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private final TenantValidationClient tenantValidationClient;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> WHITELIST =
            List.of("/actuator/**", "/api/v1/tenants/auth/register", "/api/v1/tenants/auth/login");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing Authorization Bearer token");
        }
        String apiKey = authHeader.substring("Bearer ".length());

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

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("X-Error", message);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -1; // run early
    }
}
