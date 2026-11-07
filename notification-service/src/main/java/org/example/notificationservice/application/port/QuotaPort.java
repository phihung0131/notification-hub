package org.example.notificationservice.application.port;

/** Exact quota reservation, owned by tenant-service. */
public interface QuotaPort {

    enum Result {
        GRANTED,
        EXCEEDED,
        UNKNOWN_TENANT
    }

    /**
     * Atomically hold one unit; idempotent per reservationId.
     *
     * @throws org.example.notificationservice.domain.DependencyUnavailableException when the quota
     *     service cannot answer (callers fail closed)
     */
    Result reserve(String tenantId, String reservationId);

    /** Best effort: the hold also expires by TTL on the quota side. Never throws. */
    void release(String tenantId, String reservationId);
}
