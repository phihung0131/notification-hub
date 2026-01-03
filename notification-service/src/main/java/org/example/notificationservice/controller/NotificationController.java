package org.example.notificationservice.controller;

import jakarta.validation.Valid;

import org.example.commons.baseclass.ApiResponse;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.service.NotificationOrchestrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for notification operations with fast response time (<50ms).
 */
@RestController
@RequestMapping("/send")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationOrchestrationService orchestrationService;

    /**
     * Send notification endpoint. Accepts notification request, validates, checks quota, saves to
     * DB, and publishes to Kafka. Returns 202 ACCEPTED immediately with message ID.
     *
     * @param request notification request
     * @param tenantId tenant ID from gateway (X-Tenant-Id header)
     * @return API response with message ID
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SendNotificationResponse> send(
            @RequestBody @Valid SendNotificationRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        log.info(
                "Received notification request from tenant: {}, channel: {}",
                tenantId,
                request.getChannel());

        SendNotificationResponse response = orchestrationService.send(request, tenantId);

        return ApiResponse.ok(response);
    }
}
