package org.example.notificationservice.mapper;

import org.example.events.NotificationEvent;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.util.MapUtil;

import java.time.Instant;
import java.util.UUID;

public class NotificationMapper {
    public static NotificationEvent toEventDto(SendNotificationRequest entity) {
        NotificationEvent dto = new NotificationEvent();

        MapUtil.copyProperties(entity, dto);
        dto.setId(UUID.randomUUID());
        dto.setCreatedAt(Instant.now());

        return dto;
    }
}
