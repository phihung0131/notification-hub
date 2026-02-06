package org.example.deliveryservice.service.model;

import org.example.events.enums.NotificationStatus;

public record DeliveryResult(NotificationStatus status, String detail) {
    public static DeliveryResult success() {
        return new DeliveryResult(NotificationStatus.SENT, "Mock send success");
    }

    public static DeliveryResult failure(String reason) {
        return new DeliveryResult(NotificationStatus.FAILED, reason);
    }
}
