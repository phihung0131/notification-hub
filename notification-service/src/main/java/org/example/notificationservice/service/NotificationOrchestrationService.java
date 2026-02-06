package org.example.notificationservice.service;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.model.Notification;
import org.example.notificationservice.repository.ChannelRepository;
import org.example.notificationservice.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification Orchestration Service - Facade Pattern. Orchestrates all notification-related
 * operations by delegating to specialized services. This is the main entry point for sending
 * notifications.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOrchestrationService {

    private final NotificationValidationService validationService;
    private final QuotaCheckService quotaCheckService;
    private final NotificationPublisherService publisherService;
    private final NotificationRepository notificationRepository;
    private final ChannelRepository channelRepository;

    /**
     * Send notification. Orchestrates the entire notification sending flow.
     *
     * @param request notification request
     * @param tenantId tenant ID from gateway header
     * @return send notification response with message ID
     */
    @Transactional
    public SendNotificationResponse send(SendNotificationRequest request, String tenantId) {
        log.info(
                "Processing notification request for tenant: {}, channel: {}",
                tenantId,
                request.getChannel());

        // Step 1: Validate request
        validationService.validateRequest(request);

        // Step 2: Check quota (fail-fast if no quota)
        if (quotaCheckService.hasAvailableQuota(tenantId) <= 0) {
            log.warn("Quota exceeded for tenant: {}", tenantId);
            throw new BaseException(ApiErrorMessage.QUOTA_EXCEEDED);
        }

        // Step 3: Get channel
        Channel channel =
                channelRepository
                        .findByCode(request.getChannel())
                        .orElseThrow(() -> new BaseException(ApiErrorMessage.CHANNEL_NOT_FOUND));

        // Step 4: Save notification to database (PENDING status)
        Notification notification = createNotification(request, tenantId, channel);
        notification = notificationRepository.save(notification);

        log.debug("Notification saved to database: id={}", notification.getId());

        // Step 5: Publish to Kafka
        String messageId = publisherService.publishNotification(notification);

        // Step 6: Decrement quota (asynchronously)
        quotaCheckService.decrementQuotaAsync(tenantId);

        // Step 7: Build and return response
        SendNotificationResponse response =
                SendNotificationResponse.builder()
                        .id(messageId)
                        .tenantId(tenantId)
                        .notificationStatus(
                                org.example.notificationservice.common.enums.NotificationStatus
                                        .PENDING)
                        .message("Notification request accepted and queued for processing")
                        .build();

        log.info("Notification sent successfully: messageId={}", messageId);

        return response;
    }

    /** Create notification entity from request. */
    private Notification createNotification(
            SendNotificationRequest request, String tenantId, Channel channel) {
        return Notification.builder()
                .tenantId(tenantId)
                .channel(channel)
                .recipient(request.getRecipient())
                .subject(request.getSubject())
                .content(request.getContent())
                .status(org.example.notificationservice.common.enums.NotificationStatus.PENDING)
                .build();
    }
}
