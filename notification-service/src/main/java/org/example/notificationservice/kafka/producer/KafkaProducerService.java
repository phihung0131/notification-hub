package org.example.notificationservice.kafka.producer;

import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka producer service for publishing notification events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.requested}")
    private String requestedTopic;

    /**
     * Publishes a notification event to the notification.requested topic.
     *
     * @param event the notification event to publish (must not be null)
     * @throws org.springframework.kafka.KafkaException if publishing fails
     */
    public void sendRequested(NotificationEvent event) {
        log.info("Publishing notification.requested to {}: {}", requestedTopic, event);
        kafkaTemplate.send(requestedTopic, event.getId().toString(), event);
    }
}
