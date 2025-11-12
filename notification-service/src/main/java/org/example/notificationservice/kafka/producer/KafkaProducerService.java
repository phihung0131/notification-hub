package org.example.notificationservice.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public void sendMessage(String message) {
        log.debug("Sending message to kafka: {}", message);
        kafkaTemplate.send("notification-topic", message);
    }
}
