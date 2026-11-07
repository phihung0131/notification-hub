package org.example.notificationservice.grpc.client;

import java.util.concurrent.TimeUnit;

import org.example.proto.tenant.CheckQuotaRequest;
import org.example.proto.tenant.CheckQuotaResponse;
import org.example.proto.tenant.ReleaseQuotaRequest;
import org.example.proto.tenant.ReleaseQuotaResponse;
import org.example.proto.tenant.ReserveQuotaRequest;
import org.example.proto.tenant.ReserveQuotaResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;

/**
 * Tenant Service gRPC Client. Every call carries a deadline so a slow tenant-service cannot hold
 * request threads (the caller fails closed on timeout).
 */
@Component
@Slf4j
public class TenantServiceGrpcClient {

    @GrpcClient("tenant-service")
    private TenantServiceGrpc.TenantServiceBlockingStub tenantServiceStub;

    @Value("${notification.quota.deadline-ms:1000}")
    private long deadlineMs;

    @Value("${notification.quota.reservation-ttl-seconds:900}")
    private int reservationTtlSeconds;

    private TenantServiceGrpc.TenantServiceBlockingStub stub() {
        return tenantServiceStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    /** Non-binding quota view (kept for diagnostics). */
    public CheckQuotaResponse checkQuota(String tenantId) throws StatusRuntimeException {
        return stub().checkQuota(CheckQuotaRequest.newBuilder().setTenantId(tenantId).build());
    }

    /** Atomically reserve one unit; idempotent per reservationId. */
    public ReserveQuotaResponse reserveQuota(String tenantId, String reservationId)
            throws StatusRuntimeException {
        return stub().reserveQuota(
                        ReserveQuotaRequest.newBuilder()
                                .setTenantId(tenantId)
                                .setReservationId(reservationId)
                                .setTtlSeconds(reservationTtlSeconds)
                                .build());
    }

    /** Drop a reservation (request aborted before it was durably accepted). */
    public ReleaseQuotaResponse releaseQuota(String tenantId, String reservationId)
            throws StatusRuntimeException {
        return stub().releaseQuota(
                        ReleaseQuotaRequest.newBuilder()
                                .setTenantId(tenantId)
                                .setReservationId(reservationId)
                                .build());
    }
}
