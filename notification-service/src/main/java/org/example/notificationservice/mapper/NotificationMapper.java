package org.example.notificationservice.mapper;

import java.time.Instant;
import java.util.UUID;

import org.example.commons.util.MapUtil;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.notificationservice.dto.request.SendNotificationRequest;

public class NotificationMapper {
    public static NotificationEvent toEventDto(SendNotificationRequest entity) {
        NotificationEvent dto = new NotificationEvent();

        MapUtil.copyProperties(entity, dto);
        dto.setId(UUID.randomUUID());
        dto.setCreatedAt(Instant.now());
        dto.setStatus(NotificationStatus.PENDING);

        return dto;
    }
}
