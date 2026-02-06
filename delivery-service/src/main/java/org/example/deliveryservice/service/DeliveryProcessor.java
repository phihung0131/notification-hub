package org.example.deliveryservice.service;

import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.service.adapter.DeliveryAdapter;
import org.example.deliveryservice.service.model.DeliveryResult;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Delivery Processor Service. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryProcessor {

    private final DeliveryAdapterRegistry adapterRegistry;
    private final KafkaProducerService producerService;

    @Value("${app.delivery.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.delivery.retry.initial-backoff-ms:500}")
    private long initialBackoffMs;

    /**
     * Process notification delivery with automatic retry logic.
     *
     * @param event the notification event to deliver (must not be null)
     * @throws IllegalArgumentException if event or event.channel is null
     */
    public void process(NotificationEvent event) {
        DeliveryAdapter adapter = adapterRegistry.resolve(event.getChannel());
        int attempt = 0;
        DeliveryResult result = DeliveryResult.failure("Not attempted");

        while (attempt < maxAttempts) {
            attempt++;
            result = adapter.deliver(event);
            if (result.status() == NotificationStatus.SENT) {
                break;
            }
            // simple exponential backoff
            long sleepMs = initialBackoffMs * (1L << (attempt - 1));
            log.warn(
                    "Attempt {}/{} failed for message {}. Backing off {} ms. Reason: {}",
                    attempt,
                    maxAttempts,
                    event.getId(),
                    sleepMs,
                    result.detail());
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = DeliveryResult.failure("Interrupted during retry backoff");
                break;
            }
        }

        NotificationEvent resultEvent =
                NotificationEvent.newBuilder(event).setStatus(result.status()).build();

        if (result.status() == NotificationStatus.SENT) {
            producerService.sendResult(resultEvent);
            log.info("Delivery success for message {} after {} attempt(s)", event.getId(), attempt);
        } else {
            producerService.sendDlq(resultEvent, result.detail());
            producerService.sendResult(resultEvent);
            log.error(
                    "Delivery failed for message {} after {} attempt(s). Sent to DLQ.",
                    event.getId(),
                    attempt);
        }
    }
}
