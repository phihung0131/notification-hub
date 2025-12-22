package org.example.tenantservice.kafka.consumer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.tenantservice.model.ProcessedEvent;
import org.example.tenantservice.repository.ProcessedEventRepository;
import org.example.tenantservice.service.QuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification Result Consumer - Saga Pattern Implementation. Consumes notification delivery
 * results and updates quota accordingly. Only increments quota when status is SENT (successful
 * delivery).
 *
 * <p>Features: - Batch processing for performance - Idempotency via ProcessedEvent tracking -
 * Manual acknowledgment for reliability - Scheduled cleanup of old processed events
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationResultConsumer {

    private final QuotaService quotaService;
    private final ProcessedEventRepository processedEventRepository;

    @Value("${app.kafka.topic.result:notification.result}")
    private String resultTopic;

    @Value("${app.quota.batch-size:50}")
    private int batchSize;

    @Value("${app.quota.flush-interval-ms:5000}")
    private long flushIntervalMs;

    private final List<NotificationEvent> buffer = Collections.synchronizedList(new ArrayList<>());
    private volatile long lastFlush = System.currentTimeMillis();
    private final String NOTIFICATION_RESULT = "NOTIFICATION_RESULT";

    /**
     * Consume notification result events. Only processes SENT status (successful delivery). Uses
     * manual acknowledgment for at-least-once delivery guarantee.
     *
     * @param event notification event
     * @param acknowledgment Kafka acknowledgment
     */
    @KafkaListener(
            topics = "${app.kafka.topic.result}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(NotificationEvent event, Acknowledgment acknowledgment) {
        try {
            log.debug(
                    "Received notification result: messageId={}, status={}, tenantId={}",
                    event.getId(),
                    event.getStatus(),
                    event.getTenantId());

            // Only process SENT status (Saga Pattern - only charge for successful delivery)
            if (event.getStatus() != NotificationStatus.SENT) {
                log.debug("Skipping event with status: {}", event.getStatus());
                acknowledgment.acknowledge();
                return;
            }

            // Add to buffer for batch processing
            buffer.add(event);

            // Acknowledge message immediately after buffering
            acknowledgment.acknowledge();

            // Check if batch size threshold reached
            if (buffer.size() >= batchSize) {
                flushBuffer("batch-size-threshold");
            }

        } catch (Exception e) {
            log.error("Error consuming notification result event: {}", e.getMessage(), e);
            // Don't acknowledge on error - will be redelivered
            throw e;
        }
    }

    /**
     * Scheduled flush - processes buffered events periodically. Runs every 5 seconds
     * (configurable).
     */
    @Scheduled(fixedDelayString = "${app.quota.flush-interval-ms:5000}")
    public void scheduledFlush() {
        if (!buffer.isEmpty() && (System.currentTimeMillis() - lastFlush) >= flushIntervalMs) {
            flushBuffer("scheduled");
        }
    }

    /**
     * Flush buffered events and update quotas. Implements idempotency check to prevent duplicate
     * processing.
     *
     * @param reason reason for flushing
     */
    @Transactional
    public void flushBuffer(String reason) {
        List<NotificationEvent> snapshot;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(buffer);
            buffer.clear();
        }
        lastFlush = System.currentTimeMillis();

        log.info("Flushing {} events (reason: {})", snapshot.size(), reason);

        // Filter out already processed events (idempotency)
        List<NotificationEvent> newEvents =
                snapshot.stream()
                        .filter(
                                event ->
                                        !processedEventRepository.existsByMessageId(
                                                event.getId().toString()))
                        .toList();

        if (newEvents.isEmpty()) {
            log.debug("All events already processed, skipping");
            return;
        }

        log.info("Processing {} new events out of {} total", newEvents.size(), snapshot.size());

        // Group by tenant and count
        Map<String, Long> increments =
                newEvents.stream()
                        .collect(
                                Collectors.groupingBy(
                                        event -> event.getTenantId(), Collectors.counting()));

        // Update quota for each tenant
        increments.forEach(
                (tenantId, increment) -> {
                    try {
                        quotaService.incrementQuotaUsed(tenantId, increment.intValue());
                        log.info(
                                "Quota incremented for tenant {} by {} (reason: {})",
                                tenantId,
                                increment,
                                reason);
                    } catch (Exception e) {
                        log.error(
                                "Error incrementing quota for tenant {}: {}",
                                tenantId,
                                e.getMessage(),
                                e);
                        // Continue processing other tenants
                    }
                });

        // Mark events as processed (idempotency)
        List<ProcessedEvent> processedEvents =
                newEvents.stream()
                        .map(
                                event ->
                                        ProcessedEvent.builder()
                                                .messageId(event.getId().toString())
                                                .tenantId(event.getTenantId())
                                                .eventType(NOTIFICATION_RESULT)
                                                .processedAt(Instant.now())
                                                .build())
                        .toList();

        processedEventRepository.saveAll(processedEvents);

        log.info(
                "Successfully processed {} events for {} tenants",
                newEvents.size(),
                increments.size());
    }

    /** Cleanup old processed events (runs daily). Keeps events for 7 days for audit purposes. */
    @Scheduled(cron = "0 0 2 * * *") // Run at 2 AM daily
    @Transactional
    public void cleanupOldProcessedEvents() {
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            processedEventRepository.deleteOlderThan(cutoff);
            log.info("Cleaned up processed events older than {}", cutoff);
        } catch (Exception e) {
            log.error("Error cleaning up old processed events: {}", e.getMessage(), e);
        }
    }
}
