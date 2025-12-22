package org.example.tenantservice.grpc.server;

import org.example.proto.tenant.*;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.service.ApiKeyValidationService;
import org.example.tenantservice.service.QuotaService;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC service implementation for tenant operations with high-performance RPC endpoints.
 *
 * <p>This service provides critical synchronous operations used by other microservices:
 *
 * <ul>
 *   <li><strong>API Key Validation:</strong> Used by Gateway Service for request authentication
 *   <li><strong>Quota Checking:</strong> Used by Notification Service for preliminary quota
 *       verification
 * </ul>
 *
 * <h2>Design Principles:</h2>
 *
 * <ul>
 *   <li><strong>Interface Segregation (ISP):</strong> Focused interface with only essential
 *       operations
 *   <li><strong>Fail-Fast:</strong> Input validation before processing
 *   <li><strong>Error Handling:</strong> Comprehensive exception handling with appropriate gRPC
 *       status codes
 *   <li><strong>Performance:</strong> Redis caching for API key validation, fast quota checks
 * </ul>
 *
 * <h2>Service Operations:</h2>
 *
 * <h3>1. validateApiKey()</h3>
 *
 * <p><strong>Caller:</strong> Gateway Service
 *
 * <p><strong>Purpose:</strong> Authenticate API requests
 *
 * <p><strong>Caching:</strong> Results cached in Redis via ApiKeyValidationService
 *
 * <p>Returns tenant info (ID, status, plan) if valid, or isValid=false if revoked/not found
 *
 * <h3>2. checkQuota()</h3>
 *
 * <p><strong>Caller:</strong> Notification Service
 *
 * <p><strong>Purpose:</strong> Preliminary quota check before accepting notifications
 *
 * <p><strong>Note:</strong> This is NOT for accurate billing (use Saga pattern for that)
 *
 * <p>Returns quota status: allowed, remaining, limit
 *
 * <h2>gRPC Status Codes:</h2>
 *
 * <ul>
 *   <li><strong>OK:</strong> Successful operation (isValid=true/false returned)
 *   <li><strong>INVALID_ARGUMENT:</strong> Missing or empty required fields
 *   <li><strong>INTERNAL:</strong> Unexpected server errors (DB connection, etc.)
 * </ul>
 *
 * <h2>Example Usage (Notification Service):</h2>
 *
 * <pre>{@code
 * // Check quota before accepting notification
 * CheckQuotaRequest request = CheckQuotaRequest.newBuilder()
 *     .setTenantId(tenantId)
 *     .build();
 *
 * CheckQuotaResponse response = tenantServiceStub.checkQuota(request);
 * if (!response.getIsAllowed()) {
 *     throw new QuotaExceededException();
 * }
 * }</pre>
 *
 * <h2>Thread Safety:</h2>
 *
 * <p>This service is stateless and thread-safe. gRPC manages concurrent requests.
 *
 * @author Notification Hub Team
 * @version 1.0
 * @since 1.0
 * @see ApiKeyValidationService
 * @see QuotaService
 * @see org.example.proto.tenant.TenantServiceGrpc
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class TenantGrpcService extends TenantServiceGrpc.TenantServiceImplBase {

    private final ApiKeyValidationService apiKeyValidationService;
    private final QuotaService quotaService;

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
