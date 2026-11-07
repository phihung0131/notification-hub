package org.example.notificationservice.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated, accepted notification ready to be persisted/published.
 *
 * @param id notification id; also the quota reservation id
 * @param idempotencyKey client key (null when the request had none)
 * @param fingerprint SHA-256 of the canonical request payload
 */
public record NewNotification(
        UUID id,
        String tenantId,
        String channelCode,
        String recipient,
        String subject,
        String content,
        String priority,
        String idempotencyKey,
        String fingerprint,
        Instant createdAt) {}
