package org.example.deliveryservice.service;

import java.util.Optional;
import java.util.UUID;

import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.state.DeliveryState;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.events.NotificationEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies provider status callbacks. Duplicate callbacks are dropped by event id; out-of-order
 * callbacks cannot regress the state (rank guard in SQL). A status event is published only when the
 * state really advanced. Dedupe insert, state update and publish share one transaction, so a failed
 * publish rolls the dedupe row back and the provider's retry is processed again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        IGNORED_STALE,
        UNKNOWN_NOTIFICATION
    }

    private final DeliveryStateRepository state;
    private final KafkaProducerService producer;
    private final TransactionTemplate tx;

    /** Provider event types: sent, delivered, bounced. */
    public static Optional<DeliveryState> mapType(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type.toLowerCase()) {
            case "sent", "accepted" -> Optional.of(DeliveryState.SENT);
            case "delivered" -> Optional.of(DeliveryState.DELIVERED);
            case "bounced", "failed" -> Optional.of(DeliveryState.BOUNCED);
            default -> Optional.empty();
        };
    }

    public Outcome apply(String eventId, UUID notificationId, DeliveryState target) {
        return tx.execute(
                status -> {
                    var row = state.find(notificationId);
                    if (row.isEmpty()) {
                        // callback raced ahead of our own record: let the provider retry later
                        return Outcome.UNKNOWN_NOTIFICATION;
                    }
                    if (!state.recordProviderEvent(eventId, notificationId)) {
                        return Outcome.DUPLICATE;
                    }
                    if (!state.advance(notificationId, target, null)) {
                        log.info("Stale/out-of-order {} for {} ignored", target, notificationId);
                        return Outcome.IGNORED_STALE;
                    }
                    var r = row.get();
                    producer.sendResult(
                            NotificationEvent.newBuilder()
                                    .setId(notificationId)
                                    .setTenantId(r.tenantId())
                                    .setChannel(r.channel())
                                    .setRecipient(r.recipient())
                                    .setStatus(target.toEventStatus())
                                    .setCreatedAt(java.time.Instant.now())
                                    .build());
                    state.markResultPublished(notificationId);
                    return Outcome.APPLIED;
                });
    }
}
