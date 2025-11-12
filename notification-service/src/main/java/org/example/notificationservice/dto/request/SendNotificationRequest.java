package org.example.notificationservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

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
