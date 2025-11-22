package org.example.tenantservice.grpc.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.example.proto.tenant.GetTenantQuotaRequest;
import org.example.proto.tenant.GetTenantQuotaResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.TenantRepository;

import java.util.Optional;

@GrpcService
@RequiredArgsConstructor
@Slf4j
public class TenantGrpcService extends TenantServiceGrpc.TenantServiceImplBase {

    final TenantRepository tenantRepository;

    /**
     * Get Tenant Quota
     * @param request GetTenantQuotaRequest
     * @param responseObserver StreamObserver<GetTenantQuotaResponse>
     */
    @Override
    public void getTenantQuota(GetTenantQuotaRequest request, StreamObserver<GetTenantQuotaResponse> responseObserver) {
        log.debug("Received GetTenantQuotaRequest: {}", request);
        if (request.getTenantId().trim().isEmpty()) {
            Status status = Status.INVALID_ARGUMENT.withDescription("Tenant ID must be provided.");
            responseObserver.onError(new StatusRuntimeException(status));
            return;
        }

        Optional<Tenant> tenant = tenantRepository.findById(request.getTenantId());

        if (tenant.isEmpty()) {
            Status status = Status.INVALID_ARGUMENT.withDescription("Tenant not found.");
            responseObserver.onError(new StatusRuntimeException(status));
            return;
        }

        int quotaQuantity = tenant.get().getQuotaLimit() < request.getQuotaQuantity()? tenant.get().getQuotaLimit() : request.getQuotaQuantity();

        GetTenantQuotaResponse response = GetTenantQuotaResponse.newBuilder()
                .setQuotaQuantity(quotaQuantity)
                .build();

        responseObserver.onNext(response);

//        tenant.get().setQuotaLimit(tenant.get().getQuotaLimit() - quotaQuantity);
//        tenantRepository.save(tenant.get());

        responseObserver.onCompleted();
    }
}
