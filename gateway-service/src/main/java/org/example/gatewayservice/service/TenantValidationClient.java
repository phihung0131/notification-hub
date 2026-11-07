package org.example.gatewayservice.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.example.gatewayservice.exception.InvalidCredentialsException;
import org.example.gatewayservice.exception.TenantServiceUnavailableException;
import org.example.gatewayservice.model.ApiKeyValidationResponse;
import org.example.gatewayservice.model.JwtValidationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Validates credentials against tenant-service without blocking the Netty event loop.
 *
 * <ul>
 *   <li>The blocking gRPC call runs on {@code boundedElastic} with a deadline.
 *   <li>Valid API keys are cached for a short TTL (revocation lag is at most the TTL). In-flight
 *       lookups for one key are coalesced; failures are never cached.
 *   <li>Invalid keys are negatively cached for a few seconds to blunt key-guessing floods.
 *   <li>Errors are classified: {@link InvalidCredentialsException} only for a definitive "no";
 *       everything else is {@link TenantServiceUnavailableException} (HTTP 503).
 * </ul>
 */
@Component
@Slf4j
public class TenantValidationClient {

    private final TenantRpc rpc;
    private final Duration deadline;
    private final AsyncCache<String, ApiKeyValidationResponse> validKeys;
    private final Cache<String, Boolean> invalidKeys;

    public TenantValidationClient(
            TenantRpc rpc,
            @Value("${app.tenant-service.grpc-deadline:2s}") Duration deadline,
            @Value("${app.auth.cache.ttl:30s}") Duration ttl,
            @Value("${app.auth.cache.negative-ttl:5s}") Duration negativeTtl,
            @Value("${app.auth.cache.max-size:10000}") long maxSize) {
        this.rpc = rpc;
        this.deadline = deadline;
        this.validKeys =
                Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(ttl).buildAsync();
        this.invalidKeys =
                Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(negativeTtl).build();
    }

    public Mono<ApiKeyValidationResponse> validateApiKey(String apiKey) {
        if (invalidKeys.getIfPresent(apiKey) != null) {
            return Mono.error(new InvalidCredentialsException("Invalid API key"));
        }
        return Mono.fromFuture(() -> validKeys.get(apiKey, (k, executor) -> lookupApiKey(k)))
                // never keep a failed lookup (Caffeine drops it asynchronously; be immediate)
                .doOnError(e -> validKeys.synchronous().invalidate(apiKey))
                .onErrorMap(
                        java.util.concurrent.CompletionException.class,
                        e -> e.getCause() != null ? e.getCause() : e)
                .doOnError(
                        InvalidCredentialsException.class,
                        e -> invalidKeys.put(apiKey, Boolean.TRUE));
    }

    private java.util.concurrent.CompletableFuture<ApiKeyValidationResponse> lookupApiKey(
            String apiKey) {
        return Mono.fromCallable(() -> rpc.validateApiKey(apiKey, deadline))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        r -> {
                            if (!r.getIsValid()) {
                                throw new InvalidCredentialsException("Invalid API key");
                            }
                            return new ApiKeyValidationResponse(
                                    r.getTenantId(),
                                    (Set<String>) new HashSet<>(r.getPermissionsList()));
                        })
                .onErrorMap(this::classify)
                .toFuture();
    }

    public Mono<JwtValidationResponse> validateJwt(String token) {
        return Mono.fromCallable(() -> rpc.validateJwt(token, deadline))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        r -> {
                            if (!r.getIsValid()) {
                                throw new InvalidCredentialsException("Invalid JWT");
                            }
                            return new JwtValidationResponse(
                                    r.getTenantId(), new HashSet<>(r.getPermissionsList()));
                        })
                .onErrorMap(this::classify);
    }

    /** Definitive rejection stays 401; any transport/server problem becomes 503. */
    private Throwable classify(Throwable t) {
        if (t instanceof InvalidCredentialsException
                || t instanceof TenantServiceUnavailableException) {
            return t;
        }
        if (t instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            if (code == Status.Code.UNAUTHENTICATED
                    || code == Status.Code.PERMISSION_DENIED
                    || code == Status.Code.NOT_FOUND
                    || code == Status.Code.INVALID_ARGUMENT) {
                return new InvalidCredentialsException("Credentials rejected: " + code);
            }
            log.warn("tenant-service gRPC error: {}", sre.getStatus());
            return new TenantServiceUnavailableException("tenant-service " + code, t);
        }
        log.warn("tenant-service call failed: {}", t.toString());
        return new TenantServiceUnavailableException("tenant-service call failed", t);
    }
}
