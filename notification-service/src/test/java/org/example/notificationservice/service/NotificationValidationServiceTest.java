package org.example.notificationservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationValidationService Unit Tests")
class NotificationValidationServiceTest {

    @Mock private ChannelRepository channelRepository;

    @InjectMocks private NotificationValidationService validationService;

    private Channel emailChannel;
    private SendNotificationRequest validRequest;

    @BeforeEach
    void setUp() {
        emailChannel =
                Channel.builder().id("channel-1").code("email").name("Email").active(true).build();

        validRequest = new SendNotificationRequest();
        validRequest.setChannel("email");
        validRequest.setRecipient("test@example.com");
        validRequest.setSubject("Test");
        validRequest.setContent("Test content");
    }

    @Test
    @DisplayName("Should validate successfully for valid email request")
    void validateRequest_ValidEmail_Success() {
        // Arrange
        when(channelRepository.findByCode("email")).thenReturn(Optional.of(emailChannel));

        // Act & Assert
        assertDoesNotThrow(() -> validationService.validateRequest(validRequest));
    }

    @Test
    @DisplayName("Should throw exception for invalid email format")
    void validateRequest_InvalidEmail_ThrowsException() {
        // Arrange
        validRequest.setRecipient("invalid-email");
        // No need to stub channelRepository - validation fails before channel check

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.INVALID_EMAIL_FORMAT.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for empty recipient")
    void validateRequest_EmptyRecipient_ThrowsException() {
        // Arrange
        validRequest.setRecipient("");

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.INVALID_RECIPIENT.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for null recipient")
    void validateRequest_NullRecipient_ThrowsException() {
        // Arrange
        validRequest.setRecipient(null);

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.INVALID_RECIPIENT.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for channel not found")
    void validateRequest_ChannelNotFound_ThrowsException() {
        // Arrange
        when(channelRepository.findByCode(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.CHANNEL_NOT_FOUND.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for inactive channel")
    void validateRequest_InactiveChannel_ThrowsException() {
        // Arrange
        emailChannel.setActive(false);
        when(channelRepository.findByCode("email")).thenReturn(Optional.of(emailChannel));

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.CHANNEL_INACTIVE.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for empty content")
    void validateRequest_EmptyContent_ThrowsException() {
        // Arrange
        validRequest.setContent("");
        when(channelRepository.findByCode("email")).thenReturn(Optional.of(emailChannel));

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.EMPTY_CONTENT.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should throw exception for content too long")
    void validateRequest_ContentTooLong_ThrowsException() {
        // Arrange
        validRequest.setContent("a".repeat(10001)); // Max is 10000
        when(channelRepository.findByCode("email")).thenReturn(Optional.of(emailChannel));

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.CONTENT_TOO_LONG.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("Should validate successfully for valid phone number (SMS)")
    void validateRequest_ValidPhone_Success() {
        // Arrange
        Channel smsChannel = Channel.builder().code("sms").name("SMS").active(true).build();

        validRequest.setChannel("sms");
        validRequest.setRecipient("+84987654321");
        when(channelRepository.findByCode("sms")).thenReturn(Optional.of(smsChannel));

        // Act & Assert
        assertDoesNotThrow(() -> validationService.validateRequest(validRequest));
    }

    @Test
    @DisplayName("Should throw exception for invalid phone format")
    void validateRequest_InvalidPhone_ThrowsException() {
        // Arrange
        validRequest.setChannel("sms");
        validRequest.setRecipient("invalid-phone");
        // No need to stub channelRepository - validation fails before channel check

        // Act & Assert
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> validationService.validateRequest(validRequest));
        assertEquals(ApiErrorMessage.INVALID_PHONE_FORMAT.getCode(), exception.getCode());
    }
}
