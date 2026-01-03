package org.example.notificationservice.dto.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class SendNotificationRequest {
    @NotBlank(message = "Channel is required")
    String channel;

    @NotBlank(message = "Recipient is required")
    String recipient;

    String tenantId;
    String apiKey;
    String templateId;

    @Size(max = 255, message = "Subject must not exceed 255 chars")
    private String subject;

    private String content;
    private Map<String, String> variables;
}
