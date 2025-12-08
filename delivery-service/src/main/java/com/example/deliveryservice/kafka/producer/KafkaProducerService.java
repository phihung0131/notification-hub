package com.example.deliveryservice.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.example.events.NotificationEvent;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendMessage(NotificationEvent eventDto) {
        log.info("Sending message to kafka: {}", eventDto);
        kafkaTemplate.send("notification-topic", eventDto);
    }
}
