package com.example.analyticsservice.kafka.consumer;

import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.analyticsservice.service.MessageBatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Kafka consumer for notification.result events in Analytics Service. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationResultConsumer {

    private final MessageBatchService batchService;

    /**
     * Consumes notification result events from Kafka.
     *
     * @param event the notification result event with final status
     */
    @KafkaListener(
            topics = "${app.kafka.topic.result}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(NotificationEvent event) {
        log.info("Analytics received result: {}", event);
        batchService.handleResult(event);
    }
}
