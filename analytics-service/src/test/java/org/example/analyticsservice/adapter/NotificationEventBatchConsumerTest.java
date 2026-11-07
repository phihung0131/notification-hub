package org.example.analyticsservice.adapter;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.analyticsservice.adapter.kafka.NotificationEventBatchConsumer;
import org.example.analyticsservice.application.MessageIngestService;
import org.example.events.NotificationEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;

class NotificationEventBatchConsumerTest {

    private final MessageIngestService ingest = mock(MessageIngestService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final NotificationEventBatchConsumer consumer =
            new NotificationEventBatchConsumer(ingest);

    private List<ConsumerRecord<String, NotificationEvent>> records() {
        NotificationEvent e =
                NotificationEvent.newBuilder().setId(UUID.randomUUID()).setTenantId("t").build();
        return List.of(new ConsumerRecord<>("topic", 0, 0L, "k", e));
    }

    @Test
    void acksOnlyAfterIngest() {
        consumer.onRequested(records(), ack);
        InOrder order = inOrder(ingest, ack);
        order.verify(ingest).ingest(any());
        order.verify(ack).acknowledge();
    }

    @Test
    void doesNotAckWhenIngestFails() {
        doThrow(new IllegalStateException("db")).when(ingest).ingest(any());
        assertThrows(IllegalStateException.class, () -> consumer.onResult(records(), ack));
        verify(ack, never()).acknowledge();
    }
}
