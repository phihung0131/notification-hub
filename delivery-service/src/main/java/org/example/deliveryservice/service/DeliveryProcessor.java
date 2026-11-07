package org.example.deliveryservice.service;

import java.util.UUID;

import org.example.deliveryservice.guard.RecipientGuard;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.provider.ChannelProvider.ProviderResult;
import org.example.deliveryservice.provider.DeliveryException;
import org.example.deliveryservice.state.DeliveryState;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.events.NotificationEvent;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * One delivery attempt. No sleeping and no in-process retry: failures are thrown and the Kafka
 * error handler routes them to the next retry tier or the DLQ. Safe to call repeatedly for the same
 * notification (redelivery, retry, replay): the processed-id store prevents a second send.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryProcessor {

    private final ChannelDispatcher dispatcher;
    private final KafkaProducerService producerService;
    private final DeliveryStateRepository state;
    private final RecipientGuard guard;

    public void process(NotificationEvent event, String lane) {
        if (event.getId() == null) {
            throw DeliveryException.permanent("notification id missing", null);
        }
        UUID id = event.getId();
        String key = id.toString();

        boolean claimedNow =
                state.insertIfAbsent(
                        id,
                        str(event.getTenantId()),
                        str(event.getChannel()),
                        str(event.getRecipient()));
        if (!claimedNow) {
            var row = state.find(id).orElseThrow();
            if (row.state() != DeliveryState.IN_PROGRESS) {
                if (!row.resultPublished()) {
                    // sent earlier but the result event never made it: publish it, do not re-send
                    publishResult(event, row.state());
                    log.info("Redelivery of {}: republished missing result", key);
                } else {
                    log.info("Redelivery of {} ignored: already {}", key, row.state());
                }
                return;
            }
        }

        if (state.isOptedOut(str(event.getTenantId()), str(event.getRecipient()))) {
            suppress(event, "recipient opted out");
            return;
        }

        var decision = guard.check(event, key);
        switch (decision.verdict()) {
            case DUPLICATE -> {
                suppress(event, "duplicate content suppressed");
                return;
            }
            case THROTTLED ->
                    throw DeliveryException.retryableAfter(
                            "recipient throttled", decision.retryAfter());
            default -> {}
        }

        ProviderResult result = dispatcher.dispatch(event, key, lane);

        if (state.advance(id, DeliveryState.SENT, result.providerMessageId())) {
            publishResult(event, DeliveryState.SENT);
            log.info("Delivered {} via {} ({})", key, event.getChannel(), result.detail());
        } else {
            log.info("State of {} already advanced past SENT, not publishing SENT", key);
        }
    }

    private void suppress(NotificationEvent event, String reason) {
        if (state.failIfInProgress(event.getId(), reason)) {
            publishResult(event, DeliveryState.FAILED);
            log.info("Suppressed {}: {}", event.getId(), reason);
        }
    }

    /** Publish result (awaited, acks=all) then flag it so redelivery will not repeat it. */
    void publishResult(NotificationEvent event, DeliveryState st) {
        NotificationEvent out =
                NotificationEvent.newBuilder(event).setStatus(st.toEventStatus()).build();
        producerService.sendResult(out);
        state.markResultPublished(event.getId());
    }

    private static String str(CharSequence c) {
        return c == null ? null : c.toString();
    }
}
