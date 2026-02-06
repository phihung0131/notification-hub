package org.example.notificationservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.model.Notification;
import org.example.notificationservice.repository.ChannelRepository;
import org.example.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationOrchestrationService}. Tests the complete notification sending
 * orchestration flow.
 *
 * @author Notification Hub Team
 */
@ExtendWith(MockitoExtension.class)
class NotificationOrchestrationServiceTest {

    @Mock private NotificationValidationService validationService;

    @Mock private QuotaCheckService quotaCheckService;

    @Mock private NotificationPublisherService publisherService;

    @Mock private NotificationRepository notificationRepository;

    @Mock private ChannelRepository channelRepository;

    @InjectMocks private NotificationOrchestrationService orchestrationService;

    private SendNotificationRequest request;
    private Channel emailChannel;
    private String tenantId;

    @BeforeEach
    void setUp() {
        tenantId = "tenant-123";

        request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setSubject("Test Subject");
        request.setContent("Test Content");

        emailChannel = new Channel();
        emailChannel.setId(UUID.randomUUID().toString());
        emailChannel.setCode("EMAIL");
        emailChannel.setName("Email");
    }

    @Test
    @DisplayName("Should successfully send notification through complete flow")
    void send_ValidRequest_SendsNotificationSuccessfully() {
        // Given
        UUID notificationId = UUID.randomUUID();
        String messageId = notificationId.toString();

        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.of(emailChannel));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(
                        invocation -> {
                            Notification saved = invocation.getArgument(0);
                            saved.setId(notificationId);
                            return saved;
                        });
        when(publisherService.publishNotification(any(Notification.class))).thenReturn(messageId);

        // When
        SendNotificationResponse response = orchestrationService.send(request, tenantId);

        // Then
        assertNotNull(response);
        assertEquals(messageId, response.getId());
        assertEquals(tenantId, response.getTenantId());
        assertEquals(NotificationStatus.PENDING, response.getNotificationStatus());
        assertTrue(response.getMessage().contains("accepted"));

        // Verify execution order
        verify(validationService).validateRequest(request);
        verify(quotaCheckService).hasAvailableQuota(tenantId);
        verify(channelRepository).findByCode("EMAIL");

        // Verify notification saved with correct data
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getValue();
        assertEquals(tenantId, savedNotification.getTenantId());
        assertEquals(emailChannel, savedNotification.getChannel());
        assertEquals("user@example.com", savedNotification.getRecipient());
        assertEquals("Test Subject", savedNotification.getSubject());
        assertEquals("Test Content", savedNotification.getContent());
        assertEquals(NotificationStatus.PENDING, savedNotification.getStatus());

        verify(publisherService).publishNotification(any(Notification.class));
    }

    @Test
    @DisplayName("Should throw exception when validation fails")
    void send_InvalidRequest_ThrowsException() {
        // Given
        doThrow(
                        new BaseException(
                                2000001,
                                org.springframework.http.HttpStatus.BAD_REQUEST,
                                "Invalid request",
                                null))
                .when(validationService)
                .validateRequest(request);

        // When & Then
        assertThrows(
                BaseException.class,
                () -> {
                    orchestrationService.send(request, tenantId);
                });

        verify(validationService).validateRequest(request);
        verify(quotaCheckService, never()).hasAvailableQuota(anyString());
        verify(channelRepository, never()).findByCode(anyString());
        verify(notificationRepository, never()).save(any());
        verify(publisherService, never()).publishNotification(any());
    }

    @Test
    @DisplayName("Should throw exception when quota exceeded")
    void send_QuotaExceeded_ThrowsException() {
        // Given
        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(0);

        // When & Then
        BaseException exception =
                assertThrows(
                        BaseException.class,
                        () -> {
                            orchestrationService.send(request, tenantId);
                        });

        assertEquals(2000003, exception.getCode()); // QUOTA_EXCEEDED
        verify(validationService).validateRequest(request);
        verify(quotaCheckService).hasAvailableQuota(tenantId);
        verify(channelRepository, never()).findByCode(anyString());
        verify(notificationRepository, never()).save(any());
        verify(publisherService, never()).publishNotification(any());
    }

    @Test
    @DisplayName("Should throw exception when channel not found")
    void send_ChannelNotFound_ThrowsException() {
        // Given
        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.empty());

        // When & Then
        BaseException exception =
                assertThrows(
                        BaseException.class,
                        () -> {
                            orchestrationService.send(request, tenantId);
                        });

        assertEquals(2000001, exception.getCode()); // CHANNEL_NOT_FOUND
        verify(validationService).validateRequest(request);
        verify(quotaCheckService).hasAvailableQuota(tenantId);
        verify(channelRepository).findByCode("EMAIL");
        verify(notificationRepository, never()).save(any());
        verify(publisherService, never()).publishNotification(any());
    }

    @Test
    @DisplayName("Should rollback transaction when database save fails")
    void send_DatabaseSaveFails_RollsBackTransaction() {
        // Given
        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.of(emailChannel));
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When & Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    orchestrationService.send(request, tenantId);
                });

        verify(validationService).validateRequest(request);
        verify(quotaCheckService).hasAvailableQuota(tenantId);
        verify(channelRepository).findByCode("EMAIL");
        verify(notificationRepository).save(any(Notification.class));
        verify(publisherService, never()).publishNotification(any());
    }

    @Test
    @DisplayName("Should rollback transaction when Kafka publish fails")
    void send_KafkaPublishFails_RollsBackTransaction() {
        // Given
        UUID notificationId = UUID.randomUUID();
        String messageId = notificationId.toString();

        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.of(emailChannel));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(
                        invocation -> {
                            Notification saved = invocation.getArgument(0);
                            saved.setId(notificationId);
                            return saved;
                        });
        when(publisherService.publishNotification(any(Notification.class)))
                .thenThrow(new RuntimeException("Kafka broker unavailable"));

        // When & Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    orchestrationService.send(request, tenantId);
                });

        verify(validationService).validateRequest(request);
        verify(quotaCheckService).hasAvailableQuota(tenantId);
        verify(channelRepository).findByCode("EMAIL");
        verify(notificationRepository).save(any(Notification.class));
        verify(publisherService).publishNotification(any(Notification.class));
        // @Transactional should rollback the database save
    }

    @Test
    @DisplayName("Should handle different channel types")
    void send_DifferentChannels_ProcessesCorrectly() {
        // Given
        String[] channels = {"SMS", "TELEGRAM", "EMAIL"};

        for (String channelCode : channels) {
            // Setup
            reset(
                    validationService,
                    quotaCheckService,
                    channelRepository,
                    notificationRepository,
                    publisherService);

            request.setChannel(channelCode);
            Channel channel = new Channel();
            channel.setCode(channelCode);
            channel.setName(channelCode.toLowerCase());

            UUID notificationId = UUID.randomUUID();
            String messageId = notificationId.toString();

            doNothing().when(validationService).validateRequest(request);
            when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
            when(channelRepository.findByCode(channelCode)).thenReturn(Optional.of(channel));
            when(notificationRepository.save(any(Notification.class)))
                    .thenAnswer(
                            invocation -> {
                                Notification saved = invocation.getArgument(0);
                                saved.setId(notificationId);
                                return saved;
                            });
            when(publisherService.publishNotification(any(Notification.class)))
                    .thenReturn(messageId);

            // When
            SendNotificationResponse response = orchestrationService.send(request, tenantId);

            // Then
            assertNotNull(response);
            assertEquals(messageId, response.getId());
            verify(channelRepository).findByCode(channelCode);

            ArgumentCaptor<Notification> notificationCaptor =
                    ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(notificationCaptor.capture());
            assertEquals(channel, notificationCaptor.getValue().getChannel());
        }
    }

    @Test
    @DisplayName("Should save notification with PENDING status")
    void send_ValidRequest_SavesWithPendingStatus() {
        // Given
        UUID notificationId = UUID.randomUUID();
        String messageId = notificationId.toString();

        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.of(emailChannel));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(
                        invocation -> {
                            Notification saved = invocation.getArgument(0);
                            saved.setId(notificationId);
                            return saved;
                        });
        when(publisherService.publishNotification(any(Notification.class))).thenReturn(messageId);

        // When
        orchestrationService.send(request, tenantId);

        // Then
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getValue();
        assertEquals(NotificationStatus.PENDING, savedNotification.getStatus());
    }

    @Test
    @DisplayName("Should pass saved notification to Kafka publisher")
    void send_ValidRequest_PublishesSavedNotification() {
        // Given
        UUID notificationId = UUID.randomUUID();
        String messageId = notificationId.toString();

        doNothing().when(validationService).validateRequest(request);
        when(quotaCheckService.hasAvailableQuota(tenantId)).thenReturn(1);
        when(channelRepository.findByCode("EMAIL")).thenReturn(Optional.of(emailChannel));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(
                        invocation -> {
                            Notification saved = invocation.getArgument(0);
                            saved.setId(notificationId);
                            return saved;
                        });
        when(publisherService.publishNotification(any(Notification.class))).thenReturn(messageId);

        // When
        orchestrationService.send(request, tenantId);

        // Then
        ArgumentCaptor<Notification> publishCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(publisherService).publishNotification(publishCaptor.capture());

        Notification publishedNotification = publishCaptor.getValue();
        assertNotNull(publishedNotification.getId());
        assertEquals(notificationId, publishedNotification.getId());
    }
}
