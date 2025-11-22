package org.example.tenantservice.kafka.dto;

import lombok.Data;
import org.example.tenantservice.common.enums.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

@Data
public class NotificationEventDto {
    private UUID id;
    private String tenantId;
    private String channel;
    private String recipient;
    private String subject;
    private String content;
    private NotificationStatus status;
    private Instant createdAt;
}

