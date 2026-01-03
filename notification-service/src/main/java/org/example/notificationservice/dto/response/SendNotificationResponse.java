package org.example.notificationservice.dto.response;

import org.example.notificationservice.common.enums.NotificationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendNotificationResponse {
    private String id;
    private String tenantId;
    private NotificationStatus notificationStatus;
    private String message;
}
