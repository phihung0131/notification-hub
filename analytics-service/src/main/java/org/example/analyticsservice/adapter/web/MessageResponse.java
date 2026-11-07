package org.example.analyticsservice.adapter.web;

import java.time.Instant;
import java.util.UUID;

import org.example.analyticsservice.domain.MessageRecord;
import org.example.events.enums.NotificationStatus;

public record MessageResponse(
        UUID messageId,
        String tenantId,
        String channel,
        String recipient,
        String subject,
        String content,
        NotificationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    static MessageResponse from(MessageRecord r) {
        return new MessageResponse(
                r.messageId(),
                r.tenantId(),
                r.channel(),
                r.recipient(),
                r.subject(),
                r.content(),
                r.status(),
                r.createdAt(),
                r.updatedAt());
    }
}
