package org.example.notificationservice.kafka.producer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.example.events.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class KafkaProducerServiceTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private KafkaProducerService producerService;

    private static final String REQUESTED_TOPIC = "notification.requested";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(producerService, "requestedTopic", REQUESTED_TOPIC);
    }

    @Test
    @DisplayName("Should publish notification event to correct topic")
    void sendRequested_ValidEvent_PublishesToTopic() {
        // Given
        UUID eventId = UUID.randomUUID();
        NotificationEvent event =
                NotificationEvent.newBuilder()
                        .setId(eventId)
                        .setTenantId("tenant-123")
                        .setChannel("EMAIL")
                        .setRecipient("user@example.com")
                        .setSubject("Test")
                        .setContent("Content")
                        .build();

        // When
        producerService.sendRequested(event);

        // Then
        verify(kafkaTemplate).send(eq(REQUESTED_TOPIC), eq(eventId.toString()), eq(event));
    }

    @Test
    @DisplayName("Should use event ID as Kafka message key")
    void sendRequested_ValidEvent_UsesEventIdAsKey() {
        // Given
        UUID eventId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        NotificationEvent event =
                NotificationEvent.newBuilder()
                        .setId(eventId)
                        .setTenantId("tenant-123")
                        .setChannel("SMS")
                        .setRecipient("+1234567890")
                        .setSubject("")
                        .setContent("SMS Content")
                        .build();

        // When
        producerService.sendRequested(event);

        // Then
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

        verify(kafkaTemplate).send(eq(REQUESTED_TOPIC), keyCaptor.capture(), valueCaptor.capture());

        assertEquals("550e8400-e29b-41d4-a716-446655440000", keyCaptor.getValue());
        assertEquals(event, valueCaptor.getValue());
    }

    @Test
    @DisplayName("Should publish event with correct Avro schema")
    void sendRequested_ValidEvent_PublishesAvroMessage() {
        // Given
        UUID eventId = UUID.randomUUID();
        NotificationEvent event =
                NotificationEvent.newBuilder()
                        .setId(eventId)
                        .setTenantId("tenant-456")
                        .setChannel("TELEGRAM")
                        .setRecipient("@username")
                        .setSubject("Telegram Message")
                        .setContent("Hello Telegram!")
                        .build();

        // When
        producerService.sendRequested(event);

        // Then
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), anyString(), valueCaptor.capture());

        Object publishedValue = valueCaptor.getValue();
        assertInstanceOf(NotificationEvent.class, publishedValue);

        NotificationEvent publishedEvent = (NotificationEvent) publishedValue;
        assertEquals(eventId, publishedEvent.getId());
        assertEquals("tenant-456", publishedEvent.getTenantId());
        assertEquals("TELEGRAM", publishedEvent.getChannel());
        assertEquals("@username", publishedEvent.getRecipient());
    }

    @Test
    @DisplayName("Should handle different channel types")
    void sendRequested_DifferentChannels_PublishesAll() {
        // Given
        String[] channels = {"EMAIL", "SMS", "TELEGRAM"};

        for (String channel : channels) {
            reset(kafkaTemplate);

            UUID eventId = UUID.randomUUID();
            NotificationEvent event =
                    NotificationEvent.newBuilder()
                            .setId(eventId)
                            .setTenantId("tenant-test")
                            .setChannel(channel)
                            .setRecipient("recipient")
                            .setSubject("Subject")
                            .setContent("Content")
                            .build();

            // When
            producerService.sendRequested(event);

            // Then
            verify(kafkaTemplate).send(eq(REQUESTED_TOPIC), eq(eventId.toString()), eq(event));
        }
    }

    @Test
    @DisplayName("Should publish multiple events sequentially")
    void sendRequested_MultipleEvents_PublishesSequentially() {
        // Given
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        NotificationEvent event1 =
                NotificationEvent.newBuilder()
                        .setId(id1)
                        .setTenantId("tenant-1")
                        .setChannel("EMAIL")
                        .setRecipient("user1@example.com")
                        .setSubject("Subject 1")
                        .setContent("Content 1")
                        .build();

        NotificationEvent event2 =
                NotificationEvent.newBuilder()
                        .setId(id2)
                        .setTenantId("tenant-2")
                        .setChannel("SMS")
                        .setRecipient("+1234567890")
                        .setSubject("")
                        .setContent("Content 2")
                        .build();

        // When
        producerService.sendRequested(event1);
        producerService.sendRequested(event2);

        // Then
        verify(kafkaTemplate, times(2))
                .send(anyString(), anyString(), any(NotificationEvent.class));
        verify(kafkaTemplate).send(eq(REQUESTED_TOPIC), eq(id1.toString()), eq(event1));
        verify(kafkaTemplate).send(eq(REQUESTED_TOPIC), eq(id2.toString()), eq(event2));
    }
}
