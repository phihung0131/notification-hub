package org.example.deliveryservice.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.service.adapter.DeliveryAdapter;
import org.example.deliveryservice.service.model.DeliveryResult;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryProcessor Unit Tests")
class DeliveryProcessorTest {

    @Mock private DeliveryAdapterRegistry adapterRegistry;

    @Mock private KafkaProducerService producerService;

    @Mock private DeliveryAdapter mockAdapter;

    @InjectMocks private DeliveryProcessor deliveryProcessor;

    private NotificationEvent testEvent;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(deliveryProcessor, "maxAttempts", 3);
        ReflectionTestUtils.setField(deliveryProcessor, "initialBackoffMs", 100L);

        testEvent =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-123")
                        .setChannel("email")
                        .setRecipient("test@example.com")
                        .setSubject("Test")
                        .setContent("Test content")
                        .setStatus(NotificationStatus.PENDING)
                        .build();
    }

    @Test
    @DisplayName("Should succeed on first attempt")
    void process_SuccessFirstAttempt_PublishesResult() {
        // Arrange
        when(adapterRegistry.resolve("email")).thenReturn(mockAdapter);
        when(mockAdapter.deliver(testEvent)).thenReturn(DeliveryResult.success());

        // Act
        deliveryProcessor.process(testEvent);

        // Assert
        verify(mockAdapter, times(1)).deliver(testEvent);
        verify(producerService).sendResult(any(NotificationEvent.class));
        verify(producerService, never()).sendDlq(any(), anyString());
    }

    @Test
    @DisplayName("Should retry and succeed on second attempt")
    void process_SuccessSecondAttempt_PublishesResult() {
        // Arrange
        when(adapterRegistry.resolve("email")).thenReturn(mockAdapter);
        when(mockAdapter.deliver(testEvent))
                .thenReturn(DeliveryResult.failure("Temporary error"))
                .thenReturn(DeliveryResult.success());

        // Act
        deliveryProcessor.process(testEvent);

        // Assert
        verify(mockAdapter, times(2)).deliver(testEvent);
        verify(producerService).sendResult(any(NotificationEvent.class));
        verify(producerService, never()).sendDlq(any(), anyString());
    }

    @Test
    @DisplayName("Should fail after max retries and send to DLQ")
    void process_FailAfterMaxRetries_SendsToDlq() {
        // Arrange
        when(adapterRegistry.resolve("email")).thenReturn(mockAdapter);
        when(mockAdapter.deliver(testEvent))
                .thenReturn(DeliveryResult.failure("Error 1"))
                .thenReturn(DeliveryResult.failure("Error 2"))
                .thenReturn(DeliveryResult.failure("Error 3"));

        // Act
        deliveryProcessor.process(testEvent);

        // Assert
        verify(mockAdapter, times(3)).deliver(testEvent);
        verify(producerService).sendDlq(any(NotificationEvent.class), anyString());
        verify(producerService).sendResult(any(NotificationEvent.class));
    }

    @Test
    @DisplayName("Should handle unknown channel with default adapter")
    void process_UnknownChannel_UsesDefaultAdapter() {
        // Arrange
        NotificationEvent unknownChannelEvent =
                NotificationEvent.newBuilder(testEvent).setChannel("unknown").build();

        when(adapterRegistry.resolve("unknown")).thenReturn(mockAdapter);
        when(mockAdapter.deliver(unknownChannelEvent)).thenReturn(DeliveryResult.success());

        // Act
        deliveryProcessor.process(unknownChannelEvent);

        // Assert
        verify(adapterRegistry).resolve("unknown");
        verify(mockAdapter).deliver(unknownChannelEvent);
        verify(producerService).sendResult(any(NotificationEvent.class));
    }
}
