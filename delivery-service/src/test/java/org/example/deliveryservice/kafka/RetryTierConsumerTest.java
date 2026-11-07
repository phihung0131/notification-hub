package org.example.deliveryservice.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.consumer.RetryTierConsumer;
import org.example.deliveryservice.kafka.lane.Lane;
import org.example.deliveryservice.service.DeliveryProcessor;
import org.example.events.NotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

@DisplayName("Retry tier delay and lanes")
class RetryTierConsumerTest {

    private final DeliveryProcessor processor = mock(DeliveryProcessor.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:10Z");
    private final RetryTierConsumer consumer =
            new RetryTierConsumer(
                    processor, new DeliveryProperties(), Clock.fixed(now, ZoneOffset.UTC));

    private ConsumerRecord<String, NotificationEvent> rec(long ts) {
        return new ConsumerRecord<>(
                "notification.requested.retry.30s",
                0,
                0L,
                ts,
                TimestampType.CREATE_TIME,
                0,
                0,
                "k",
                NotificationEvent.newBuilder().build(),
                new org.apache.kafka.common.header.internals.RecordHeaders(),
                java.util.Optional.empty());
    }

    @Test
    @DisplayName("record not yet due is nacked for the remaining time and not processed")
    void notDue() {
        consumer.consume(rec(now.toEpochMilli() - 10_000), ack); // 30s tier, 10s elapsed

        verify(ack).nack(Duration.ofSeconds(20));
        verify(processor, never()).process(any(), anyString());
    }

    @Test
    @DisplayName("due record is processed and acknowledged")
    void due() {
        consumer.consume(rec(now.toEpochMilli() - 31_000), ack);

        verify(processor).process(any(), anyString());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("priority header selects lane, default normal")
    void laneFromHeader() {
        var h = new org.apache.kafka.common.header.internals.RecordHeaders();
        assertEquals(Lane.NORMAL, Lane.fromHeaders(h));
        h.add("priority", "HIGH".getBytes());
        assertEquals(Lane.HIGH, Lane.fromHeaders(h));
        var l = new org.apache.kafka.common.header.internals.RecordHeaders();
        l.add("priority", "low".getBytes());
        assertEquals(Lane.LOW, Lane.fromHeaders(l));
    }
}
