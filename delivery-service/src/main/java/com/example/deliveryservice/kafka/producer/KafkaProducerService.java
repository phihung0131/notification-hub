package com.example.deliveryservice.kafka.producer;

import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.result}")
    private String resultTopic;

    @Value("${app.kafka.topic.dlq}")
    private String dlqTopic;

    public void sendResult(NotificationEvent event) {
        log.info("Publishing delivery result to Kafka topic {}: {}", resultTopic, event);
        kafkaTemplate.send(resultTopic, event.getId().toString(), event);
    }

    public void sendDlq(NotificationEvent event, String reason) {
        log.warn("Publishing to DLQ {} for message {}: {}", dlqTopic, event.getId(), reason);
        kafkaTemplate.send(dlqTopic, event.getId().toString(), event);
    }
}
