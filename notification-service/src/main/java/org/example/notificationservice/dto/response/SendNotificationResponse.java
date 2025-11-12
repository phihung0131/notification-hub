package org.example.notificationservice.dto.response;

import lombok.Data;
import org.example.notificationservice.common.enums.NotificationStatus;

@Data
public class SendNotificationResponse {
    String notificationId;
    String tenantId;
    NotificationStatus notificationStatus;
}
