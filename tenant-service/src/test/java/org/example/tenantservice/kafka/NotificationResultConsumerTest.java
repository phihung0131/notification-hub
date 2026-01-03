package org.example.tenantservice.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.tenantservice.kafka.consumer.NotificationResultConsumer;
import org.example.tenantservice.repository.ProcessedEventRepository;
import org.example.tenantservice.service.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationResultConsumer Unit Tests")
class NotificationResultConsumerTest {

    @Mock private QuotaService quotaService;

    @Mock private ProcessedEventRepository processedEventRepository;

    @Mock private Acknowledgment acknowledgment;

    @InjectMocks private NotificationResultConsumer consumer;

    private NotificationEvent testEvent;

    @BeforeEach
    void setUp() {
        // Set configuration values
        ReflectionTestUtils.setField(consumer, "batchSize", 50);
        ReflectionTestUtils.setField(consumer, "flushIntervalMs", 5000L);

        // Create test event
        testEvent =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId(UUID.randomUUID().toString())
                        .setChannel("email")
                        .setRecipient("test@example.com")
                        .setSubject("Test")
                        .setContent("Test content")
                        .setStatus(NotificationStatus.SENT)
                        .build();
    }

    /**
     * Should consume SENT event and add to buffer
     */
    @Test
    void consume_SentEvent_AddsToBuffer() {
        // Act
        consumer.consume(testEvent, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        assertEquals(1, ((List<?>) Objects.requireNonNull(ReflectionTestUtils.getField(consumer, "buffer"))).size());
    }

    /**
     * Should skip non-SENT events
     */
    @Test
    void consume_NonSentEvent_Skips() {
        // Arrange
        NotificationEvent failedEvent =
                NotificationEvent.newBuilder(testEvent)
                        .setStatus(NotificationStatus.FAILED)
                        .build();

        // Act
        consumer.consume(failedEvent, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        assertEquals(0, ((List<?>) Objects.requireNonNull(ReflectionTestUtils.getField(consumer, "buffer"))).size());
    }

    /**
     * Should process events in batch and update quotas
     */
    @Test
    void flushBuffer_Success() {
        // Arrange
        String tenantId = UUID.randomUUID().toString();
        NotificationEvent event1 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenantId)
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenantId)
                        .build();

        when(processedEventRepository.existsByMessageId(anyString())).thenReturn(false);

        // Add events to buffer
        consumer.consume(event1, acknowledgment);
        consumer.consume(event2, acknowledgment);

        // Act
        consumer.flushBuffer("test");

        // Assert
        verify(quotaService).incrementQuotaUsed(tenantId, 2);
        verify(processedEventRepository, times(2)).existsByMessageId(anyString());
        verify(processedEventRepository).saveAll(anyList());
    }

    /**
     * Should skip already processed events (idempotency)
     */
    @Test
    void flushBuffer_AlreadyProcessed_Skips() {
        // Arrange
        when(processedEventRepository.existsByMessageId(testEvent.getId().toString()))
                .thenReturn(true);

        consumer.consume(testEvent, acknowledgment);

        // Act
        consumer.flushBuffer("test");

        // Assert
        verify(quotaService, never()).incrementQuotaUsed(anyString(), anyInt());
    }

    /**
     * Should handle multiple tenants correctly
     */
    @Test
    void flushBuffer_MultipleTenants_HandlesCorrectly() {
        // Arrange
        String tenant1 = UUID.randomUUID().toString();
        String tenant2 = UUID.randomUUID().toString();

        NotificationEvent event1 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenant1)
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenant2)
                        .build();

        NotificationEvent event3 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenant1)
                        .build();

        when(processedEventRepository.existsByMessageId(anyString())).thenReturn(false);

        consumer.consume(event1, acknowledgment);
        consumer.consume(event2, acknowledgment);
        consumer.consume(event3, acknowledgment);

        // Act
        consumer.flushBuffer("test");

        // Assert
        verify(quotaService).incrementQuotaUsed(tenant1, 2);
        verify(quotaService).incrementQuotaUsed(tenant2, 1);
    }

    /**
     * Should continue processing other tenants on error
     */
    @Test
    void flushBuffer_ErrorForOneTenant_ContinuesOthers() {
        // Arrange
        String tenant1 = UUID.randomUUID().toString();
        String tenant2 = UUID.randomUUID().toString();

        NotificationEvent event1 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenant1)
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder(testEvent)
                        .setId(UUID.randomUUID())
                        .setTenantId(tenant2)
                        .build();

        when(processedEventRepository.existsByMessageId(anyString())).thenReturn(false);
        doThrow(new RuntimeException("DB error"))
                .when(quotaService)
                .incrementQuotaUsed(eq(tenant1), anyInt());
        doNothing().when(quotaService).incrementQuotaUsed(eq(tenant2), anyInt());

        consumer.consume(event1, acknowledgment);
        consumer.consume(event2, acknowledgment);

        // Act - should not throw
        assertDoesNotThrow(() -> consumer.flushBuffer("test"));

        // Assert
        verify(quotaService).incrementQuotaUsed(tenant1, 1);
        verify(quotaService).incrementQuotaUsed(tenant2, 1);
    }
}
