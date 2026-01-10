package com.example.analyticsservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.analyticsservice.model.Message;
import com.example.analyticsservice.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageBatchService Unit Tests")
class MessageBatchServiceTest {

    private static final org.mockito.quality.Strictness STRICTNESS =
            org.mockito.quality.Strictness.LENIENT;

    @Mock private MessageRepository messageRepository;

    @InjectMocks private MessageBatchService batchService;

    @Captor private ArgumentCaptor<List<Message>> messagesCaptor;

    private NotificationEvent requestedEvent;
    private NotificationEvent resultEvent;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(batchService, "batchSize", 50);
        ReflectionTestUtils.setField(batchService, "flushIntervalMs", 5000L);

        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();

        requestedEvent =
                NotificationEvent.newBuilder()
                        .setId(messageId)
                        .setTenantId("tenant-123")
                        .setChannel("email")
                        .setRecipient("test@example.com")
                        .setSubject("Test")
                        .setContent("Test content")
                        .setStatus(NotificationStatus.PENDING)
                        .setCreatedAt(now)
                        .build();

        resultEvent =
                NotificationEvent.newBuilder()
                        .setId(messageId)
                        .setTenantId("tenant-123")
                        .setChannel("email")
                        .setRecipient("test@example.com")
                        .setSubject("Test")
                        .setContent("Test content")
                        .setStatus(NotificationStatus.SENT)
                        .setCreatedAt(now)
                        .build();
    }

    @Test
    @DisplayName("Should add requested event to buffer")
    void handleRequested_AddsToBuffer() {
        // Act
        batchService.handleRequested(requestedEvent);

        // Assert - buffer should contain the event
        // We can't directly assert buffer content, but flush will process it
    }

    @Test
    @DisplayName("Should add result event to buffer")
    void handleResult_AddsToBuffer() {
        // Act
        batchService.handleResult(resultEvent);

        // Assert - verified by flush
    }

    @Test
    @DisplayName("Should save new message when flushing requested events")
    void flush_RequestedEvent_SavesNewMessage() {
        // Arrange
        lenient().when(messageRepository.findAllById(any())).thenReturn(Collections.emptyList());

        batchService.handleRequested(requestedEvent);

        // Act
        batchService.flush("test");

        // Assert
        verify(messageRepository).saveAll(messagesCaptor.capture());
        List<Message> savedMessages = messagesCaptor.getValue();

        assertFalse(savedMessages.isEmpty());
        Message saved = savedMessages.getFirst();
        assertEquals(requestedEvent.getId().toString(), saved.getMessageId().toString());
        assertEquals(requestedEvent.getTenantId(), saved.getTenantId());
        assertEquals(NotificationStatus.PENDING, saved.getStatus());
    }

    @Test
    @DisplayName("Should update existing message with result event")
    void flush_ResultEvent_UpdatesExistingMessage() {
        // Arrange
        UUID messageId = UUID.fromString(resultEvent.getId().toString());

        Message existingMessage =
                Message.builder()
                        .messageId(messageId)
                        .tenantId(resultEvent.getTenantId())
                        .channel(resultEvent.getChannel())
                        .recipient(resultEvent.getRecipient())
                        .status(NotificationStatus.PENDING)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        lenient().when(messageRepository.findAllById(any())).thenReturn(List.of(existingMessage));

        batchService.handleResult(resultEvent);

        // Act
        batchService.flush("test");

        // Assert
        verify(messageRepository).saveAll(messagesCaptor.capture());
        List<Message> savedMessages = messagesCaptor.getValue();

        assertFalse(savedMessages.isEmpty());
        Message updated = savedMessages.getFirst();
        assertEquals(NotificationStatus.SENT, updated.getStatus());
    }

    @Test
    @DisplayName("Should handle out-of-order events (result before requested)")
    void flush_OutOfOrderEvents_HandlesCorrectly() {
        // Arrange - result arrives before requested
        lenient().when(messageRepository.findAllById(any())).thenReturn(Collections.emptyList());

        // Result arrives first (out of order)
        batchService.handleResult(resultEvent);
        batchService.flush("test-result");

        verify(messageRepository, times(1)).saveAll(any());

        // Then requested arrives
        reset(messageRepository);
        lenient().when(messageRepository.findAllById(any())).thenReturn(Collections.emptyList());

        batchService.handleRequested(requestedEvent);
        batchService.flush("test-requested");

        // Assert - both events processed
        verify(messageRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("Should not flush when buffer is empty")
    void flush_EmptyBuffer_DoesNotSave() {
        // Act
        batchService.flush("test");

        // Assert
        verify(messageRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Should handle multiple events for different tenants")
    void flush_MultipleEvents_SavesAll() {
        // Arrange
        NotificationEvent event1 =
                NotificationEvent.newBuilder(requestedEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-1")
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder(requestedEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-2")
                        .build();

        lenient().when(messageRepository.findAllById(any())).thenReturn(Collections.emptyList());

        batchService.handleRequested(event1);
        batchService.handleRequested(event2);

        // Act
        batchService.flush("test");

        // Assert
        verify(messageRepository).saveAll(messagesCaptor.capture());
        List<Message> savedMessages = messagesCaptor.getValue();
        assertEquals(2, savedMessages.size());
    }
}
