package org.example.notificationservice.adapters.kafka;

import java.time.Instant;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.notificationservice.domain.NewNotification;

/** Mapping between the domain object and the shared Avro event. */
public final class NotificationEvents {

    private NotificationEvents() {}

    public static NotificationEvent toRequestedEvent(NewNotification n) {
        return NotificationEvent.newBuilder()
                .setId(n.id())
                .setTenantId(n.tenantId())
                .setChannel(n.channelCode())
                .setRecipient(n.recipient())
                .setSubject(n.subject())
                .setContent(n.content())
                .setStatus(NotificationStatus.PENDING)
                .setCreatedAt(n.createdAt())
                .setPriority(n.priority())
                .setIdempotencyKey(n.idempotencyKey())
                .setRequestFingerprint(n.fingerprint())
                .setVersion(1)
                .setEventTimestamp(Instant.now())
                .build();
    }

    public static NewNotification fromRequestedEvent(NotificationEvent e) {
        return new NewNotification(
                e.getId(),
                e.getTenantId(),
                e.getChannel(),
                e.getRecipient(),
                e.getSubject(),
                e.getContent(),
                e.getPriority() == null ? "NORMAL" : e.getPriority(),
                e.getIdempotencyKey(),
                e.getRequestFingerprint(),
                e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt());
    }
}
