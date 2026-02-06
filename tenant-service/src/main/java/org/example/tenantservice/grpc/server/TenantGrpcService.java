package org.example.tenantservice.grpc.server;

import java.util.List;

import org.example.proto.tenant.*;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.service.ApiKeyValidationService;
import org.example.tenantservice.service.AuthService;
import org.example.tenantservice.service.QuotaService;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/** gRPC service implementation for tenant operations with high-performance RPC endpoints. */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class TenantGrpcService extends TenantServiceGrpc.TenantServiceImplBase {

    private final ApiKeyValidationService apiKeyValidationService;
    private final QuotaService quotaService;
    private final AuthService authService;

    /**
     * Validate API Key - Used by Gateway. Fast authentication check with Redis caching.
     *
     * @param request ValidateApiKeyRequest
     * @param responseObserver StreamObserver
     */
    @Override
    public void validateApiKey(
            ValidateApiKeyRequest request,
            StreamObserver<ValidateApiKeyResponse> responseObserver) {
        try {
            log.debug("Received ValidateApiKeyRequest");

            // Validate input
            request.getApiKey();
            if (request.getApiKey().trim().isEmpty()) {
                responseObserver.onError(
                        Status.INVALID_ARGUMENT
                                .withDescription("API key must be provided")
                                .asRuntimeException());
                return;
            }

            // Validate API key
            TenantInfo tenantInfo = apiKeyValidationService.validateApiKey(request.getApiKey());

            if (tenantInfo == null) {
                // Invalid or revoked API key
                ValidateApiKeyResponse response =
                        ValidateApiKeyResponse.newBuilder()
                                .setIsValid(false)
                                .setTenantId("")
                                .setStatus("")
                                .setPlan("")
                                .addAllPermissions(List.of())
                                .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            // Build response
            ValidateApiKeyResponse response =
                    ValidateApiKeyResponse.newBuilder()
                            .setIsValid(true)
                            .setTenantId(tenantInfo.getTenantId())
                            .setStatus(tenantInfo.getStatus())
                            .setPlan(tenantInfo.getPlan().name())
                            .addAllPermissions(tenantInfo.getPermissions())
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("API key validated successfully for tenant: {}", tenantInfo.getTenantId());

        } catch (Exception e) {
            log.error("Error validating API key: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Internal error during API key validation")
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    /**
     * Validate JWT - Used by Gateway. Validates JWT and extracts tenant info and permissions.
     *
     * @param request ValidateJwtRequest
     * @param responseObserver StreamObserver
     */
    @Override
    public void validateJwt(
            ValidateJwtRequest request, StreamObserver<ValidateJwtResponse> responseObserver) {

        try {
            log.debug("Received ValidateJwtRequest");

            String token = request.getToken();
            if (token.trim().isEmpty()) {
                responseObserver.onError(
                        Status.INVALID_ARGUMENT
                                .withDescription("JWT token must be provided")
                                .asRuntimeException());
                return;
            }

            // Validate JWT
            TenantInfo tenantInfo = authService.validateJwt(token);

            if (tenantInfo == null) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("Invalid JWT token")
                                .asRuntimeException());
                return;
            }

            ValidateJwtResponse response =
                    ValidateJwtResponse.newBuilder()
                            .setIsValid(true)
                            .setTenantId(tenantInfo.getTenantId())
                            .setStatus(tenantInfo.getStatus())
                            .setPlan(tenantInfo.getPlan().name())
                            .addAllPermissions(tenantInfo.getPermissions())
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug("JWT validated successfully for tenant: {}", tenantInfo.getTenantId());

        } catch (Exception e) {
            log.error("Error validating JWT", e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Internal error during JWT validation")
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    /**
     * Check Quota - Used by Notification Service. Fast preliminary quota check using Redis cache.
     * This is NOT for accurate billing, just fail-fast mechanism.
     *
     * @param request CheckQuotaRequest
     * @param responseObserver StreamObserver
     */
    @Override
    public void checkQuota(
            CheckQuotaRequest request, StreamObserver<CheckQuotaResponse> responseObserver) {
        try {
            log.debug("Received CheckQuotaRequest for tenant: {}", request.getTenantId());

            // Validate input
            request.getTenantId();
            if (request.getTenantId().trim().isEmpty()) {
                responseObserver.onError(
                        Status.INVALID_ARGUMENT
                                .withDescription("Tenant ID must be provided")
                                .asRuntimeException());
                return;
            }

            // Check quota
            boolean hasQuota = quotaService.hasAvailableQuota(request.getTenantId());
            int remaining = quotaService.getRemainingQuota(request.getTenantId());
            int limit = quotaService.getQuotaLimit(request.getTenantId());

            // Build response
            CheckQuotaResponse response =
                    CheckQuotaResponse.newBuilder()
                            .setIsAllowed(hasQuota)
                            .setRemaining(remaining)
                            .setLimit(limit)
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.debug(
                    "Quota check for tenant {}: allowed={}, remaining={}/{}",
                    request.getTenantId(),
                    hasQuota,
                    remaining,
                    limit);

        } catch (Exception e) {
            log.error(
                    "Error checking quota for tenant {}: {}",
                    request.getTenantId(),
                    e.getMessage(),
                    e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Internal error during quota check")
                            .withCause(e)
                            .asRuntimeException());
        }
    }
}
