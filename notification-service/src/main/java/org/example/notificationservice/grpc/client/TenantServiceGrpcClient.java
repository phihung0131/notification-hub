package org.example.notificationservice.grpc.client;

import org.example.proto.tenant.CheckQuotaRequest;
import org.example.proto.tenant.CheckQuotaResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;

/** Tenant Service gRPC Client. Communicates with tenant-service for quota checking. */
@Component
@Slf4j
public class TenantServiceGrpcClient {

    @GrpcClient("tenant-service")
    private TenantServiceGrpc.TenantServiceBlockingStub tenantServiceStub;

    /**
     * Check if tenant has available quota. Calls tenant-service CheckQuota RPC.
     *
     * @param tenantId tenant ID
     * @return check quota response
     * @throws StatusRuntimeException if gRPC call fails
     */
    public CheckQuotaResponse checkQuota(String tenantId) throws StatusRuntimeException {
        try {
            log.debug("Calling tenant-service checkQuota for tenant: {}", tenantId);

            CheckQuotaRequest request =
                    CheckQuotaRequest.newBuilder().setTenantId(tenantId).build();

            CheckQuotaResponse response = tenantServiceStub.checkQuota(request);

            log.debug(
                    "CheckQuota response: tenant={}, allowed={}, remaining={}/{}",
                    tenantId,
                    response.getIsAllowed(),
                    response.getRemaining(),
                    response.getLimit());

            return response;

        } catch (StatusRuntimeException e) {
            log.error("gRPC error calling checkQuota for tenant {}: {}", tenantId, e.getMessage());
            throw e;
        }
    }
}
