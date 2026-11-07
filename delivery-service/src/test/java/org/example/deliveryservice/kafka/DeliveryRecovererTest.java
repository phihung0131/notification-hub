package org.example.deliveryservice.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.provider.DeliveryException;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings({"unchecked", "rawtypes"})
@DisplayName("DeliveryRecoverer retry/DLQ publishing")
class DeliveryRecovererTest {

    private KafkaTemplate avro;
    private KafkaTemplate bytes;
    private DeliveryStateRepository state;
    private KafkaProducerService producer;
    private DeliveryRecoverer recoverer;
    private NotificationEvent event;

    @BeforeEach
    void setUp() {
        avro = mock(KafkaTemplate.class);
        bytes = mock(KafkaTemplate.class);
        state = mock(DeliveryStateRepository.class);
        producer = mock(KafkaProducerService.class);
        when(avro.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        recoverer =
                new DeliveryRecoverer(
                        new RetryRouter(new DeliveryProperties(), "notification.dlq"),
                        state,
                        producer,
                        avro,
                        bytes);
        event =
                NotificationEvent.newBuilder()
                        .setId(UUID.randomUUID())
                        .setTenantId("t")
                        .setChannel("sms")
                        .setRecipient("r")
                        .build();
    }

    private ConsumerRecord<String, Object> record(int attempt) {
        ConsumerRecord<String, Object> r =
                new ConsumerRecord<>(
                        "notification.requested", 3, 7L, event.getId().toString(), event);
        if (attempt > 0) {
            r.headers().add("x-attempt", String.valueOf(attempt).getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private ProducerRecord<String, Object> sent() {
        ArgumentCaptor<ProducerRecord> c = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(avro).send(c.capture());
        return c.getValue();
    }

    private static String header(ProducerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        assertNotNull(h, "missing header " + name);
        return new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("transient failure goes to retry tier with attempt header, no result event")
    void retryTier() {
        recoverer.accept(
                record(0),
                new RuntimeException(DeliveryException.retryable("503", (Throwable) null)));

        ProducerRecord<String, Object> out = sent();
        assertEquals("notification.requested.retry.5s", out.topic());
        assertEquals("1", header(out, "x-attempt"));
        verify(producer, never()).sendResult(any());
    }

    @Test
    @DisplayName(
            "exhausted record: FAILED result published, DLQ record carries reason/class/attempt/original topic")
    void dlqHeaders() {
        when(state.failIfInProgress(any(), anyString())).thenReturn(true);

        recoverer.accept(
                record(3),
                new RuntimeException(
                        DeliveryException.retryable("provider 503", (Throwable) null)));

        ProducerRecord<String, Object> out = sent();
        assertEquals("notification.dlq", out.topic());
        assertEquals("4", header(out, "x-attempt"));
        assertEquals("provider 503", header(out, "x-failure-reason"));
        assertEquals(DeliveryException.class.getName(), header(out, "x-failure-class"));
        assertEquals("notification.requested", header(out, "x-original-topic"));
        assertNotNull(out.headers().lastHeader("x-failed-at"));
        assertNotNull(out.headers().lastHeader("kafka_dlt-exception-fqcn"));

        ArgumentCaptor<NotificationEvent> res = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(producer).sendResult(res.capture());
        assertEquals(NotificationStatus.FAILED, res.getValue().getStatus());
        verify(state).markResultPublished(event.getId());
    }

    @Test
    @DisplayName("permanent failure goes straight to DLQ")
    void permanentToDlq() {
        recoverer.accept(
                record(0), new RuntimeException(DeliveryException.permanent("invalid", null)));
        assertEquals("notification.dlq", sent().topic());
    }

    @Test
    @DisplayName("no FAILED result when the notification was already sent (state advanced)")
    void noFailedResultAfterSent() {
        when(state.failIfInProgress(any(), anyString())).thenReturn(false);
        recoverer.accept(
                record(3),
                new RuntimeException(DeliveryException.retryable("x", (Throwable) null)));
        verify(producer, never()).sendResult(any());
    }
}
