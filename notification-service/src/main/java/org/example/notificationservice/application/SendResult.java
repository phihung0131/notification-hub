package org.example.notificationservice.application;

import org.example.notificationservice.dto.response.SendNotificationResponse;

/**
 * @param replayed true when this is the stored response of an earlier identical request
 */
public record SendResult(SendNotificationResponse response, boolean replayed) {}
