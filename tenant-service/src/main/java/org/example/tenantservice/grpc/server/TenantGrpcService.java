package org.example.tenantservice.grpc.server;

import java.util.List;

import org.example.proto.tenant.CheckQuotaRequest;
import org.example.proto.tenant.CheckQuotaResponse;
import org.example.proto.tenant.CommitQuotaRequest;
import org.example.proto.tenant.CommitQuotaResponse;
import org.example.proto.tenant.ReleaseQuotaRequest;
import org.example.proto.tenant.ReleaseQuotaResponse;
import org.example.proto.tenant.ReserveQuotaRequest;
import org.example.proto.tenant.ReserveQuotaResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.example.proto.tenant.ValidateApiKeyRequest;
import org.example.proto.tenant.ValidateApiKeyResponse;
import org.example.proto.tenant.ValidateJwtRequest;
import org.example.proto.tenant.ValidateJwtResponse;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.quota.application.QuotaReservationService;
import org.example.tenantservice.quota.application.QuotaReservationService.ReserveOutcome;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.QuotaSnapshot;
import org.example.tenantservice.quota.domain.ReserveResult;
import org.example.tenantservice.service.ApiKeyValidationService;
import org.example.tenantservice.service.AuthService;

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
    private final QuotaReservationService quotaService;
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

            // Non-binding view (ledger first, durable fallback)
            QuotaSnapshot snap = quotaService.check(request.getTenantId());
            boolean hasQuota = snap != null && (snap.unlimited() || snap.remaining() > 0);
            int remaining = snap == null ? 0 : snap.remaining();
            int limit = snap == null ? 0 : snap.limit();

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

    /**
     * Reserve one quota unit atomically. Ledger failure maps to UNAVAILABLE so callers fail closed.
     */
    @Override
    public void reserveQuota(
            ReserveQuotaRequest request, StreamObserver<ReserveQuotaResponse> responseObserver) {
        if (request.getTenantId().isBlank() || request.getReservationId().isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("tenantId and reservationId are required")
                            .asRuntimeException());
            return;
        }
        try {
            ReserveOutcome outcome =
                    quotaService.reserve(
                            request.getTenantId(),
                            request.getReservationId(),
                            request.getTtlSeconds() > 0
                                    ? java.time.Duration.ofSeconds(request.getTtlSeconds())
                                    : null);
            ReserveQuotaResponse.Builder b =
                    ReserveQuotaResponse.newBuilder()
                            .setGranted(outcome.result() == ReserveResult.GRANTED);
            switch (outcome.result()) {
                case GRANTED -> b.setReason("OK");
                case EXCEEDED -> b.setReason("QUOTA_EXCEEDED");
                case UNKNOWN_TENANT -> b.setReason("UNKNOWN_TENANT");
            }
            if (outcome.snapshot() != null) {
                int held = outcome.snapshot().held();
                int limit = outcome.snapshot().limit();
                b.setLimit(limit)
                        .setRemaining(
                                limit < 0
                                        ? -1
                                        : Math.max(0, limit - outcome.snapshot().used() - held));
            }
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error(
                    "reserveQuota failed for tenant {}: {}", request.getTenantId(), e.getMessage());
            responseObserver.onError(
                    Status.UNAVAILABLE
                            .withDescription("Quota ledger unavailable")
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    /** Count a delivered notification durably. Idempotent per reservationId. */
    @Override
    public void commitQuota(
            CommitQuotaRequest request, StreamObserver<CommitQuotaResponse> responseObserver) {
        try {
            CommitOutcome outcome =
                    quotaService.commit(request.getTenantId(), request.getReservationId());
            responseObserver.onNext(
                    CommitQuotaResponse.newBuilder()
                            .setCommitted(outcome == CommitOutcome.COMMITTED)
                            .setReason(outcome.name())
                            .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error(
                    "commitQuota failed for tenant {}: {}", request.getTenantId(), e.getMessage());
            responseObserver.onError(
                    Status.UNAVAILABLE
                            .withDescription("Quota commit unavailable")
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    /** Drop a hold. Idempotent. */
    @Override
    public void releaseQuota(
            ReleaseQuotaRequest request, StreamObserver<ReleaseQuotaResponse> responseObserver) {
        try {
            boolean released =
                    quotaService.release(request.getTenantId(), request.getReservationId());
            responseObserver.onNext(
                    ReleaseQuotaResponse.newBuilder().setReleased(released).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error(
                    "releaseQuota failed for tenant {}: {}", request.getTenantId(), e.getMessage());
            responseObserver.onError(
                    Status.UNAVAILABLE
                            .withDescription("Quota release unavailable")
                            .withCause(e)
                            .asRuntimeException());
        }
    }
}
