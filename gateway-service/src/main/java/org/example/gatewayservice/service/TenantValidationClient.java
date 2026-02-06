package org.example.gatewayservice.service;

import java.util.HashSet;

import org.example.gatewayservice.model.ApiKeyValidationResponse;
import org.example.gatewayservice.model.JwtValidationResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.example.proto.tenant.ValidateApiKeyRequest;
import org.example.proto.tenant.ValidateApiKeyResponse;
import org.example.proto.tenant.ValidateJwtRequest;
import org.example.proto.tenant.ValidateJwtResponse;
import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import reactor.core.publisher.Mono;

/**
 * Tenant Validation Client using gRPC. Communicates with tenant-service for high-performance API
 * key validation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantValidationClient {

    @GrpcClient("tenant-service")
    private TenantServiceGrpc.TenantServiceBlockingStub tenantServiceStub;

    /**
     * Validate API key via gRPC. Returns Mono for reactive gateway compatibility.
     *
     * @param apiKey the API key to validate
     * @return Mono of validation response
     */
    public Mono<ApiKeyValidationResponse> validateApiKey(String apiKey) {
        return Mono.fromCallable(
                () -> {
                    try {
                        log.debug("Validating API key via gRPC: {}...", maskApiKey(apiKey));

                        ValidateApiKeyRequest request =
                                ValidateApiKeyRequest.newBuilder().setApiKey(apiKey).build();

                        ValidateApiKeyResponse response = tenantServiceStub.validateApiKey(request);

                        if (!response.getIsValid()) {
                            log.warn("Invalid API key: {}...", maskApiKey(apiKey));
                            throw new RuntimeException("Invalid API key");
                        }

                        log.debug(
                                "API key validated successfully for tenant: {}",
                                response.getTenantId());

                        return new ApiKeyValidationResponse(
                                response.getTenantId(),
                                new HashSet<>(response.getPermissionsList()));

                    } catch (StatusRuntimeException e) {
                        log.error("gRPC error validating API key: {}", e.getMessage());
                        throw new RuntimeException("API key validation failed", e);
                    }
                });
    }

    public Mono<JwtValidationResponse> validateJwt(String token) {
        return Mono.fromCallable(
                () -> {
                    try {
                        log.debug("Validating Jwt via gRPC: {}...", maskApiKey(token));

                        ValidateJwtRequest request =
                                ValidateJwtRequest.newBuilder().setToken(token).build();

                        ValidateJwtResponse response = tenantServiceStub.validateJwt(request);

                        if (!response.getIsValid()) {
                            log.warn("Invalid Jwt: {}...", maskApiKey(token));
                            throw new RuntimeException("Invalid Jwt");
                        }

                        log.debug(
                                "Jwt validated successfully for tenant: {}",
                                response.getTenantId());

                        return new JwtValidationResponse(
                                response.getTenantId(),
                                new HashSet<>(response.getPermissionsList()));

                    } catch (StatusRuntimeException e) {
                        log.error("gRPC error validating Jwt: {}", e.getMessage());
                        throw new RuntimeException("Jwt validation failed", e);
                    }
                });
    }

    /** Mask API key for logging. */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 8) + "***";
    }
}
