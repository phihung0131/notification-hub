package org.example.deliveryservice.kafka.producer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes to Kafka. Producer is idempotent with acks=all (see application.yml). Sends are awaited
 * so a failed publish surfaces as an exception and the record is retried, never silently lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.result}")
    private String resultTopic;

    @Value("${app.kafka.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    public void sendResult(NotificationEvent event) {
        send(new ProducerRecord<>(resultTopic, event.getId().toString(), event));
        log.info("Published result {} status {}", event.getId(), event.getStatus());
    }

    /** Synchronous publish of an arbitrary record (lane routing, replay). */
    public void send(ProducerRecord<String, Object> record) {
        try {
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + record.topic(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed publishing to " + record.topic(), e);
        }
    }
}
