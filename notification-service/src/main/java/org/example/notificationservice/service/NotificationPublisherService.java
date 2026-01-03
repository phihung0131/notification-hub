package org.example.notificationservice.service;

import java.time.Instant;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.notificationservice.kafka.producer.KafkaProducerService;
import org.example.notificationservice.model.Notification;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification Publisher Service - Single Responsibility Principle. Handles publishing
 * notifications to Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisherService {

    private final KafkaProducerService kafkaProducerService;

    /**
     * Publish notification to Kafka notification.requested topic. Converts domain model to Avro
     * event.
     *
     * @param notification notification entity
     * @return message ID
     */
    public String publishNotification(Notification notification) {
        try {
            NotificationEvent event = buildEvent(notification);

            kafkaProducerService.sendRequested(event);

            log.info(
                    "Published notification to Kafka: messageId={}, tenantId={}, channel={}",
                    notification.getId(),
                    notification.getTenantId(),
                    notification.getChannel().getCode());

            return notification.getId().toString();

        } catch (Exception e) {
            log.error("Error publishing notification to Kafka: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish notification", e);
        }
    }

    /** Build Kafka event from notification entity. */
    private NotificationEvent buildEvent(Notification notification) {
        Instant createdAt =
                notification.getCreatedAt() != null ? notification.getCreatedAt() : Instant.now();

        return NotificationEvent.newBuilder()
                .setId(notification.getId())
                .setTenantId(notification.getTenantId())
                .setChannel(notification.getChannel().getCode())
                .setRecipient(notification.getRecipient())
                .setSubject(notification.getSubject())
                .setContent(notification.getContent())
                .setStatus(NotificationStatus.valueOf(notification.getStatus().name()))
                .setCreatedAt(createdAt)
                .build();
    }
}
