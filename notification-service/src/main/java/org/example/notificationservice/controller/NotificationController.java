package org.example.notificationservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.notificationservice.common.baseclass.ApiResponse;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/send-notification")
    public ResponseEntity<ApiResponse<SendNotificationResponse>> sendNotification(
            @RequestHeader("X-Tenant-Id") String tenantId,
//            @RequestHeader("X-Api-Key") String apiKey,
            @Valid @RequestBody SendNotificationRequest request
    ) {
//        request.setApiKey(apiKey);
        request.setTenantId(tenantId);
        SendNotificationResponse response = notificationService.sendNotification(request);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

}
