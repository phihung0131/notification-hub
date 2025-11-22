package org.example.notificationservice.grpc.client;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.example.proto.tenant.GetTenantQuotaResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TenantServiceGrpcClient {
    @GrpcClient("tenant-service")
    private TenantServiceGrpc.TenantServiceBlockingStub tenantServiceStub;

    public GetTenantQuotaResponse getTenantQuota(String tenantId, int quotaQuantity) {
        log.debug("Calling Tenant Service to get tenant quota for tenantId: {} with requested quantity: {}", tenantId, quotaQuantity);
        var request = org.example.proto.tenant.GetTenantQuotaRequest.newBuilder()
                .setTenantId(tenantId)
                .setQuotaQuantity(quotaQuantity)
                .build();
        return tenantServiceStub.getTenantQuota(request);
    }
}
