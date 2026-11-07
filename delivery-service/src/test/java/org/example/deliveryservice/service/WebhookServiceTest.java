package org.example.deliveryservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.service.WebhookService.Outcome;
import org.example.deliveryservice.state.DeliveryState;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.deliveryservice.state.DeliveryStateRepository.Row;
import org.example.deliveryservice.web.WebhookSignatureVerifier;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Webhook dedupe / out-of-order / signature")
class WebhookServiceTest {

    private DeliveryStateRepository state;
    private KafkaProducerService producer;
    private WebhookService service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        state = mock(DeliveryStateRepository.class);
        producer = mock(KafkaProducerService.class);
        service =
                new WebhookService(
                        state,
                        producer,
                        new TransactionTemplate(mock(PlatformTransactionManager.class)));
        when(state.find(id))
                .thenReturn(Optional.of(new Row(id, "t1", "sms", "r", DeliveryState.SENT, true)));
    }

    @Test
    @DisplayName("delivered callback advances state and publishes ACKNOWLEDGED")
    void applied() {
        when(state.recordProviderEvent("e1", id)).thenReturn(true);
        when(state.advance(id, DeliveryState.DELIVERED, null)).thenReturn(true);

        assertEquals(Outcome.APPLIED, service.apply("e1", id, DeliveryState.DELIVERED));

        ArgumentCaptor<NotificationEvent> c = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(producer).sendResult(c.capture());
        assertEquals(NotificationStatus.ACKNOWLEDGED, c.getValue().getStatus());
        assertEquals("t1", c.getValue().getTenantId().toString());
    }

    @Test
    @DisplayName("bounced maps to FAILED")
    void bounced() {
        when(state.recordProviderEvent("e2", id)).thenReturn(true);
        when(state.advance(id, DeliveryState.BOUNCED, null)).thenReturn(true);

        service.apply("e2", id, DeliveryState.BOUNCED);

        ArgumentCaptor<NotificationEvent> c = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(producer).sendResult(c.capture());
        assertEquals(NotificationStatus.FAILED, c.getValue().getStatus());
    }

    @Test
    @DisplayName("duplicate callback is dropped without publishing")
    void duplicate() {
        when(state.recordProviderEvent("e1", id)).thenReturn(false);

        assertEquals(Outcome.DUPLICATE, service.apply("e1", id, DeliveryState.DELIVERED));

        verify(state, never()).advance(any(), any(), any());
        verify(producer, never()).sendResult(any());
    }

    @Test
    @DisplayName("out-of-order (stale) callback cannot regress state and publishes nothing")
    void outOfOrder() {
        when(state.recordProviderEvent("e3", id)).thenReturn(true);
        when(state.advance(id, DeliveryState.SENT, null)).thenReturn(false); // already DELIVERED

        assertEquals(Outcome.IGNORED_STALE, service.apply("e3", id, DeliveryState.SENT));

        verify(producer, never()).sendResult(any());
    }

    @Test
    @DisplayName("unknown notification is reported so the provider retries")
    void unknown() {
        UUID other = UUID.randomUUID();
        when(state.find(other)).thenReturn(Optional.empty());

        assertEquals(Outcome.UNKNOWN_NOTIFICATION, service.apply("e9", other, DeliveryState.SENT));

        verify(state, never()).recordProviderEvent(any(), any());
    }

    @Test
    void typeMapping() {
        assertEquals(Optional.of(DeliveryState.DELIVERED), WebhookService.mapType("DELIVERED"));
        assertEquals(Optional.of(DeliveryState.BOUNCED), WebhookService.mapType("bounced"));
        assertTrue(WebhookService.mapType("weird").isEmpty());
    }

    @Test
    void signatureVerification() {
        DeliveryProperties p = new DeliveryProperties();
        p.getWebhook().setSecret("s3cret");
        WebhookSignatureVerifier v = new WebhookSignatureVerifier(p);
        String body = "{\"eventId\":\"1\"}";
        String sig = v.sign(body);

        assertTrue(v.verify(body, sig));
        assertTrue(v.verify(body, "sha256=" + sig));
        assertFalse(v.verify(body + " ", sig));
        assertFalse(v.verify(body, null));
        assertFalse(v.verify(body, "deadbeef"));
    }
}
