package org.example.notificationservice.service;

import java.util.regex.Pattern;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.repository.ChannelRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification Validation Service - Single Responsibility Principle. Handles all validation logic
 * for notification requests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationValidationService {

    private final ChannelRepository channelRepository;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile(
                    "^\\+?[1-9]\\d{1,14}$" // E.164 format
                    );

    /**
     * Validate notification request. Throws BaseException if validation fails.
     *
     * @param request notification request
     * @throws BaseException if validation fails
     */
    public void validateRequest(SendNotificationRequest request) {
        validateRecipient(request.getRecipient(), request.getChannel());
        validateChannel(request.getChannel());
        validateContent(request.getContent());

        log.debug(
                "Validation passed for request: channel={}, recipient={}",
                request.getChannel(),
                maskRecipient(request.getRecipient()));
    }

    /** Validate recipient based on channel type. */
    private void validateRecipient(String recipient, String channelCode) {
        if (recipient == null || recipient.trim().isEmpty()) {
            throw new BaseException(ApiErrorMessage.INVALID_RECIPIENT);
        }

        switch (channelCode.toLowerCase()) {
            case "email":
                if (!EMAIL_PATTERN.matcher(recipient).matches()) {
                    throw new BaseException(ApiErrorMessage.INVALID_EMAIL_FORMAT);
                }
                break;
            case "sms":
            case "telegram":
                if (!PHONE_PATTERN.matcher(recipient).matches()) {
                    throw new BaseException(ApiErrorMessage.INVALID_PHONE_FORMAT);
                }
                break;
            default:
                // Generic validation for other channels
                if (recipient.length() < 3 || recipient.length() > 255) {
                    throw new BaseException(ApiErrorMessage.INVALID_RECIPIENT);
                }
        }
    }

    /** Validate channel exists and is active. */
    private void validateChannel(String channelCode) {
        Channel channel =
                channelRepository
                        .findByCode(channelCode)
                        .orElseThrow(() -> new BaseException(ApiErrorMessage.CHANNEL_NOT_FOUND));

        if (!channel.isActive()) {
            throw new BaseException(ApiErrorMessage.CHANNEL_INACTIVE);
        }
    }

    /** Validate content not empty. */
    private void validateContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BaseException(ApiErrorMessage.EMPTY_CONTENT);
        }

        if (content.length() > 10000) { // Max 10K characters
            throw new BaseException(ApiErrorMessage.CONTENT_TOO_LONG);
        }
    }

    /** Mask recipient for logging (show only first 3 and last 2 characters). */
    private String maskRecipient(String recipient) {
        if (recipient == null || recipient.length() <= 5) {
            return "***";
        }
        return recipient.substring(0, 3) + "***" + recipient.substring(recipient.length() - 2);
    }
}
