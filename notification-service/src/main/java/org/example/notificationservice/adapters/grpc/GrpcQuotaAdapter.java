package org.example.notificationservice.adapters.grpc;

import org.example.notificationservice.application.port.QuotaPort;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.example.notificationservice.grpc.client.TenantServiceGrpcClient;
import org.example.proto.tenant.ReserveQuotaResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quota port backed by tenant-service gRPC. Any transport failure or deadline becomes {@link
 * DependencyUnavailableException}: the use case fails closed (503), never fail-open.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GrpcQuotaAdapter implements QuotaPort {

    private final TenantServiceGrpcClient client;

    @Override
    public Result reserve(String tenantId, String reservationId) {
        ReserveQuotaResponse r;
        try {
            r = client.reserveQuota(tenantId, reservationId);
        } catch (RuntimeException e) {
            throw new DependencyUnavailableException("quota-service", e);
        }
        if (r.getGranted()) {
            return Result.GRANTED;
        }
        return "UNKNOWN_TENANT".equals(r.getReason()) ? Result.UNKNOWN_TENANT : Result.EXCEEDED;
    }

    @Override
    public void release(String tenantId, String reservationId) {
        try {
            client.releaseQuota(tenantId, reservationId);
        } catch (RuntimeException e) {
            // the hold expires by TTL on the tenant-service side
            log.warn(
                    "Quota release failed for {} (TTL will reclaim): {}",
                    reservationId,
                    e.toString());
        }
    }
}
