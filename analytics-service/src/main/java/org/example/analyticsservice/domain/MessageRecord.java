package org.example.analyticsservice.domain;

import java.time.Instant;
import java.util.UUID;

import org.example.events.enums.NotificationStatus;

/** Tracked message snapshot. Immutable. */
public record MessageRecord(
        UUID messageId,
        String tenantId,
        String channel,
        String recipient,
        String subject,
        String content,
        NotificationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Merges two observations of the same message regardless of arrival order: status moves forward
     * only, earliest createdAt wins, null fields are filled from the other side.
     */
    public MessageRecord mergeWith(MessageRecord other) {
        return new MessageRecord(
                messageId,
                tenantId != null ? tenantId : other.tenantId,
                channel != null ? channel : other.channel,
                recipient != null ? recipient : other.recipient,
                subject != null ? subject : other.subject,
                content != null ? content : other.content,
                MessageStatusProgression.resolve(status, other.status),
                earliest(createdAt, other.createdAt),
                updatedAt);
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
