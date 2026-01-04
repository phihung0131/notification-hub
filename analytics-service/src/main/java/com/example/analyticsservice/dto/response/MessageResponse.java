package com.example.analyticsservice.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.example.events.enums.NotificationStatus;

import lombok.Data;

@Data
public class MessageResponse {
    private UUID messageId;
    private String tenantId;
    private String channel;
    private String recipient;
    private String subject;
    private String content;
    private NotificationStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
