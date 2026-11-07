package org.example.deliveryservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.example.deliveryservice.guard.RecipientGuard;
import org.example.deliveryservice.guard.RecipientGuard.Decision;
import org.example.deliveryservice.guard.RecipientGuard.Verdict;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.provider.ChannelProvider.ProviderResult;
import org.example.deliveryservice.provider.DeliveryException;
import org.example.deliveryservice.state.DeliveryState;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.deliveryservice.state.DeliveryStateRepository.Row;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryProcessor")
class DeliveryProcessorTest {

    @Mock private ChannelDispatcher dispatcher;
    @Mock private KafkaProducerService producer;
    @Mock private DeliveryStateRepository state;
    @Mock private RecipientGuard guard;

    @InjectMocks private DeliveryProcessor processor;

    private NotificationEvent event;
    private UUID id;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        event =
                NotificationEvent.newBuilder()
                        .setId(id)
                        .setTenantId("t1")
                        .setChannel("sms")
                        .setRecipient("+84900000000")
                        .setSubject("s")
                        .setContent("c")
                        .setStatus(NotificationStatus.PENDING)
                        .build();
    }

    private void claimed() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(true);
        when(state.isOptedOut("t1", "+84900000000")).thenReturn(false);
        when(guard.check(any(), eq(id.toString()))).thenReturn(new Decision(Verdict.ALLOW, null));
    }

    @Test
    @DisplayName("sends once with idempotency key = notificationId and publishes SENT")
    void success() {
        claimed();
        when(dispatcher.dispatch(event, id.toString(), "normal"))
                .thenReturn(new ProviderResult("p-1", "ok"));
        when(state.advance(id, DeliveryState.SENT, "p-1")).thenReturn(true);

        processor.process(event, "normal");

        ArgumentCaptor<NotificationEvent> out = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(producer).sendResult(out.capture());
        assertEquals(NotificationStatus.SENT, out.getValue().getStatus());
        verify(state).markResultPublished(id);
    }

    @Test
    @DisplayName("redelivery of an already delivered notification is not sent again")
    void redeliveryAfterSentIsSkipped() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(false);
        when(state.find(id))
                .thenReturn(Optional.of(new Row(id, "t1", "sms", "r", DeliveryState.SENT, true)));

        processor.process(event, "normal");

        verify(dispatcher, never()).dispatch(any(), anyString(), anyString());
        verify(producer, never()).sendResult(any());
    }

    @Test
    @DisplayName("redelivery after crash between send and result publish only republishes result")
    void redeliveryRepublishesMissingResult() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(false);
        when(state.find(id))
                .thenReturn(Optional.of(new Row(id, "t1", "sms", "r", DeliveryState.SENT, false)));

        processor.process(event, "normal");

        verify(dispatcher, never()).dispatch(any(), anyString(), anyString());
        verify(producer).sendResult(any());
        verify(state).markResultPublished(id);
    }

    @Test
    @DisplayName("redelivery of an IN_PROGRESS (failed earlier) notification is attempted again")
    void inProgressIsRetried() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(false);
        when(state.find(id))
                .thenReturn(
                        Optional.of(
                                new Row(id, "t1", "sms", "r", DeliveryState.IN_PROGRESS, false)));
        when(state.isOptedOut(any(), any())).thenReturn(false);
        when(guard.check(any(), anyString())).thenReturn(new Decision(Verdict.ALLOW, null));
        when(dispatcher.dispatch(event, id.toString(), "high"))
                .thenReturn(new ProviderResult("p", "ok"));
        when(state.advance(id, DeliveryState.SENT, "p")).thenReturn(true);

        processor.process(event, "high");

        verify(dispatcher).dispatch(event, id.toString(), "high");
    }

    @Test
    @DisplayName("provider failure propagates (no sleep, no in-process retry, no result yet)")
    void failurePropagates() {
        claimed();
        when(dispatcher.dispatch(any(), anyString(), anyString()))
                .thenThrow(DeliveryException.retryable("boom", (Throwable) null));

        assertThrows(DeliveryException.class, () -> processor.process(event, "normal"));

        verify(producer, never()).sendResult(any());
    }

    @Test
    @DisplayName("result publish failure propagates so the record is retried, not lost")
    void resultPublishFailurePropagates() {
        claimed();
        when(dispatcher.dispatch(any(), anyString(), anyString()))
                .thenReturn(new ProviderResult("p", "ok"));
        when(state.advance(id, DeliveryState.SENT, "p")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down"))
                .when(producer)
                .sendResult(any());

        assertThrows(IllegalStateException.class, () -> processor.process(event, "normal"));

        verify(state, never()).markResultPublished(any());
    }

    @Test
    @DisplayName("opted-out recipient is suppressed with FAILED result and never sent")
    void optOut() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(true);
        when(state.isOptedOut("t1", "+84900000000")).thenReturn(true);
        when(state.failIfInProgress(eq(id), anyString())).thenReturn(true);

        processor.process(event, "normal");

        ArgumentCaptor<NotificationEvent> out = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(producer).sendResult(out.capture());
        assertEquals(NotificationStatus.FAILED, out.getValue().getStatus());
        verify(dispatcher, never()).dispatch(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("throttled recipient raises a retryable exception carrying the wait")
    void throttled() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(true);
        when(state.isOptedOut(any(), any())).thenReturn(false);
        when(guard.check(any(), anyString()))
                .thenReturn(new Decision(Verdict.THROTTLED, Duration.ofSeconds(20)));

        DeliveryException ex =
                assertThrows(DeliveryException.class, () -> processor.process(event, "normal"));

        assertEquals(Duration.ofSeconds(20), ex.retryAfter());
        verify(dispatcher, never()).dispatch(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("duplicate content is suppressed")
    void duplicateSuppressed() {
        when(state.insertIfAbsent(eq(id), anyString(), anyString(), anyString())).thenReturn(true);
        when(state.isOptedOut(any(), any())).thenReturn(false);
        when(guard.check(any(), anyString())).thenReturn(new Decision(Verdict.DUPLICATE, null));
        when(state.failIfInProgress(eq(id), anyString())).thenReturn(true);

        processor.process(event, "normal");

        verify(dispatcher, never()).dispatch(any(), anyString(), anyString());
        verify(producer).sendResult(any());
    }
}
