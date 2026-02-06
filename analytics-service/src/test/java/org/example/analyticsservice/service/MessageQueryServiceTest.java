package org.example.analyticsservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.analyticsservice.dto.response.MessageResponse;
import org.example.analyticsservice.model.Message;
import org.example.analyticsservice.repository.MessageRepository;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageQueryService Unit Tests")
class MessageQueryServiceTest {

    @Mock private MessageRepository messageRepository;

    @InjectMocks private MessageQueryService queryService;

    private Message testMessage;
    private UUID testMessageId;
    private String testTenantId;

    @BeforeEach
    void setUp() {
        testMessageId = UUID.randomUUID();
        testTenantId = "tenant-123";

        testMessage =
                Message.builder()
                        .messageId(testMessageId)
                        .tenantId(testTenantId)
                        .channel("email")
                        .recipient("test@example.com")
                        .subject("Test Subject")
                        .content("Test content")
                        .status(NotificationStatus.SENT)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
    }

    @Test
    @DisplayName("Should return message by ID")
    void getById_MessageExists_ReturnsMessage() {
        // Arrange
        when(messageRepository.findById(testMessageId)).thenReturn(Optional.of(testMessage));

        // Act
        MessageResponse response = queryService.getById(testMessageId);

        // Assert
        assertNotNull(response);
        assertEquals(testMessageId, response.getMessageId());
        assertEquals(testTenantId, response.getTenantId());
        assertEquals("email", response.getChannel());
        assertEquals(NotificationStatus.SENT, response.getStatus());
    }

    @Test
    @DisplayName("Should return null when message not found")
    void getById_MessageNotFound_ReturnsNull() {
        // Arrange
        when(messageRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        // Act
        MessageResponse response = queryService.getById(testMessageId);

        // Assert
        assertNull(response);
    }

    @Test
    @DisplayName("Should return all messages for tenant")
    void listByTenant_ReturnsMessages() {
        // Arrange
        Message message2 =
                Message.builder()
                        .messageId(UUID.randomUUID())
                        .tenantId(testTenantId)
                        .channel("sms")
                        .recipient("+84987654321")
                        .status(NotificationStatus.PENDING)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(messageRepository.findByTenantIdOrderByCreatedAtDesc(testTenantId))
                .thenReturn(List.of(testMessage, message2));

        // Act
        List<MessageResponse> responses = queryService.listByTenant(testTenantId);

        // Assert
        assertNotNull(responses);
        assertEquals(2, responses.size());
        assertEquals(testMessageId, responses.getFirst().getMessageId());
    }

    @Test
    @DisplayName("Should return empty list when no messages found")
    void listByTenant_NoMessages_ReturnsEmptyList() {
        // Arrange
        when(messageRepository.findByTenantIdOrderByCreatedAtDesc(testTenantId))
                .thenReturn(Collections.emptyList());

        // Act
        List<MessageResponse> responses = queryService.listByTenant(testTenantId);

        // Assert
        assertNotNull(responses);
        assertTrue(responses.isEmpty());
    }
}
