package org.example.notificationservice.mapper;

import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.model.Notification;
import org.example.notificationservice.util.MapUtil;

public class NotificationMapper {
    public static Notification toEntity(SendNotificationRequest dto) {
        Notification entity = new Notification();

        MapUtil.copyProperties(dto, entity);

        return entity;
    }
}
