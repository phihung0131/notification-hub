package org.example.analyticsservice.kafka.consumer;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.example.analyticsservice.service.MessageBatchService;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationResultConsumerTest {

    @Mock private MessageBatchService batchService;

    @InjectMocks private NotificationResultConsumer consumer;

    @Test
    @DisplayName("Should consume result event and delegate to batch service")
    void consume_ValidResultEvent_DelegatesToBatchService() {
        // Given
        NotificationEvent event =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-123")
                        .setChannel("EMAIL")
                        .setRecipient("user@example.com")
                        .setStatus(NotificationStatus.SENT)
                        .build();

        doNothing().when(batchService).handleResult(event);

        // When
        consumer.consume(event);

        // Then
        verify(batchService).handleResult(event);
    }

    @Test
    @DisplayName("Should handle different status types")
    void consume_DifferentStatuses_ProcessesAll() {
        // Given
        NotificationStatus[] statuses = {NotificationStatus.SENT, NotificationStatus.FAILED};

        for (NotificationStatus status : statuses) {
            NotificationEvent event =
                    NotificationEvent.newBuilder()
                            .setId(UUID.randomUUID())
                            .setTenantId("tenant-test")
                            .setChannel("SMS")
                            .setRecipient("+1234567890")
                            .setStatus(status)
                            .build();

            // When
            consumer.consume(event);

            // Then
            verify(batchService).handleResult(event);
        }
    }
}
