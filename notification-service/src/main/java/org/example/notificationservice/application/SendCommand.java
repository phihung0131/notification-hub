package org.example.notificationservice.application;

import org.example.notificationservice.dto.request.SendNotificationRequest;

/**
 * @param idempotencyKey raw {@code Idempotency-Key} header (nullable)
 */
public record SendCommand(
        String tenantId, SendNotificationRequest request, String idempotencyKey) {}
