package org.example.notificationservice.controller;

import jakarta.validation.Valid;

import org.example.commons.baseclass.ApiResponse;
import org.example.commons.exception.BaseException;
import org.example.notificationservice.application.SendCommand;
import org.example.notificationservice.application.SendNotificationUseCase;
import org.example.notificationservice.application.SendResult;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** REST controller for notification operations with fast response time (<50ms). */
@RestController
@RequestMapping("/send")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final SendNotificationUseCase sendNotification;

    /**
     * Accepts a notification. Returns 202 with the message id. A repeated {@code Idempotency-Key}
     * with the same payload replays the original response (header {@code Idempotent-Replayed:
     * true}); a different payload gives 422; a concurrent in-flight duplicate gives 409.
     *
     * @param tenantId tenant ID from gateway (X-Tenant-Id header)
     * @param idempotencyKey optional client key, scoped per tenant, kept 24h
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SendNotificationResponse>> send(
            @RequestBody @Valid SendNotificationRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                    String idempotencyKey) {

        if (idempotencyKey != null
                && (idempotencyKey.isBlank()
                        || idempotencyKey.length() > 255
                        || idempotencyKey.chars().anyMatch(c -> c < 0x20 || c == 0x7f))) {
            throw new BaseException(ApiErrorMessage.INVALID_IDEMPOTENCY_KEY);
        }
        log.info(
                "Received notification request from tenant: {}, channel: {}, idempotent: {}",
                tenantId,
                request.getChannel(),
                idempotencyKey != null);

        SendResult result =
                sendNotification.send(new SendCommand(tenantId, request, idempotencyKey));

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(ApiResponse.ok(result.response()));
    }
}
