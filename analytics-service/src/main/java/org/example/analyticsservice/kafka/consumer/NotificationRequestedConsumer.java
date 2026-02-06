package org.example.analyticsservice.kafka.consumer;

import org.example.analyticsservice.service.MessageBatchService;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Kafka consumer for notification.requested events in Analytics Service. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestedConsumer {

    private final MessageBatchService batchService;

    /**
     * Consumes notification requested events from Kafka.
     *
     * @param event the notification event from notification.requested topic
     */
    @KafkaListener(
            topics = "${app.kafka.topic.requested}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(NotificationEvent event) {
        log.info("Analytics received requested: {}", event);
        batchService.handleRequested(event);
    }
}
