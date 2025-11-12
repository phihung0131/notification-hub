package org.example.notificationservice.kafka.dto;

import lombok.Data;
import org.example.notificationservice.common.enums.NotificationStatus;

import java.time.Instant;

@Data
public class NotificationEventDto {
    private String notificationId;
    private String tenantId;
    private String channelCode;
    private String recipient;
    private String subject;
    private String content;
    private NotificationStatus status;
    private Instant createdAt;
}

