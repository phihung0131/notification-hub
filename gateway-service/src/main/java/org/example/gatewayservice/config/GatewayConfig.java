package org.example.gatewayservice.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    @Bean
    public KeyResolver tenantKeyResolver() {
        return (ServerWebExchange exchange) ->
                Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"))
                        .defaultIfEmpty("anonymous");
    }
}
