package org.example.deliveryservice.kafka.consumer;

import org.example.deliveryservice.service.DeliveryProcessor;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestedConsumer {

    private final DeliveryProcessor deliveryProcessor;

    @KafkaListener(
            topics = "${app.kafka.topic.requested}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(NotificationEvent event) {
        log.info("Received notification request: {}", event);
        deliveryProcessor.process(event);
    }
}
