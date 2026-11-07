package org.example.tenantservice.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.tenantservice.quota.adapters.kafka.NotificationResultConsumer;
import org.example.tenantservice.quota.application.ResultEventProcessor;
import org.example.tenantservice.quota.domain.ResultEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;

class NotificationResultConsumerTest {

    private static NotificationEvent event(NotificationStatus status) {
        return NotificationEvent.newBuilder()
                .setId(UUID.randomUUID())
                .setTenantId("t1")
                .setStatus(status)
                .build();
    }

    @Test
    void acksOnlyAfterProcessorReturned() {
        ResultEventProcessor processor = mock(ResultEventProcessor.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        new NotificationResultConsumer(processor)
                .consume(List.of(event(NotificationStatus.SENT)), ack);
        InOrder o = inOrder(processor, ack);
        o.verify(processor).process(anyList());
        o.verify(ack).acknowledge();
    }

    @Test
    void doesNotAckWhenDurableWriteFails() {
        ResultEventProcessor processor = mock(ResultEventProcessor.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("db down")).when(processor).process(anyList());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new NotificationResultConsumer(processor)
                                .consume(List.of(event(NotificationStatus.SENT)), ack));
        verify(ack, never()).acknowledge();
    }

    @Test
    void mapsOnlySentAndFailed() {
        // PENDING / READ do not touch quota; SENT counts; FAILED releases
        NotificationEvent pending = event(NotificationStatus.PENDING);
        ResultEventProcessor processor = mock(ResultEventProcessor.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        new NotificationResultConsumer(processor)
                .consume(
                        List.of(
                                pending,
                                event(NotificationStatus.SENT),
                                event(NotificationStatus.FAILED)),
                        ack);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ResultEvent>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(processor).process(cap.capture());
        assertEquals(2, cap.getValue().size());
        assertEquals(ResultEvent.Outcome.SENT, cap.getValue().get(0).outcome());
        assertEquals(ResultEvent.Outcome.FAILED, cap.getValue().get(1).outcome());
    }

    @Test
    void undeserializableNullRecordIsSkippedNotFatal() {
        ResultEventProcessor processor = mock(ResultEventProcessor.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        new NotificationResultConsumer(processor)
                .consume(java.util.Collections.singletonList(null), ack);
        verify(processor).process(List.of());
        verify(ack).acknowledge();
    }
}
