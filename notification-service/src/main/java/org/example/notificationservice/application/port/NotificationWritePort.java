package org.example.notificationservice.application.port;

import java.util.Optional;

import org.example.notificationservice.domain.NewNotification;

public interface NotificationWritePort {

    record Existing(String id, String fingerprint) {}

    /**
     * ONE database transaction: insert the notification (PENDING) and an outbox row.
     *
     * @throws org.example.notificationservice.domain.DuplicateIdempotencyKeyException on unique
     *     (tenant_id, idempotency_key) violation
     */
    void insertWithOutbox(NewNotification notification);

    /** Final arbiter lookup after a duplicate-key rejection. */
    Optional<Existing> findByIdempotencyKey(String tenantId, String idempotencyKey);
}
