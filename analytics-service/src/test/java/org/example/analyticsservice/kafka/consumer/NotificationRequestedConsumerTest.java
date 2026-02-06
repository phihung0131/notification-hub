package org.example.analyticsservice.kafka.consumer;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.example.analyticsservice.service.MessageBatchService;
import org.example.events.NotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRequestedConsumerTest {

    @Mock private MessageBatchService batchService;

    @InjectMocks private NotificationRequestedConsumer consumer;

    @Test
    @DisplayName("Should consume requested event and delegate to batch service")
    void consume_ValidEvent_DelegatesToBatchService() {
        // Given
        NotificationEvent event =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-123")
                        .setChannel("EMAIL")
                        .setRecipient("user@example.com")
                        .setSubject("Test")
                        .setContent("Content")
                        .build();

        doNothing().when(batchService).handleRequested(event);

        // When
        consumer.consume(event);

        // Then
        verify(batchService).handleRequested(event);
    }

    @Test
    @DisplayName("Should handle multiple events sequentially")
    void consume_MultipleEvents_ProcessesAll() {
        // Given
        NotificationEvent event1 =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-1")
                        .setChannel("EMAIL")
                        .setRecipient("user1@example.com")
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("tenant-2")
                        .setChannel("SMS")
                        .setRecipient("+1234567890")
                        .build();

        // When
        consumer.consume(event1);
        consumer.consume(event2);

        // Then
        verify(batchService).handleRequested(event1);
        verify(batchService).handleRequested(event2);
    }
}
