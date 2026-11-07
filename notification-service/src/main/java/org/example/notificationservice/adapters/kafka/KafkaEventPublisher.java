package org.example.notificationservice.adapters.kafka;

import java.util.concurrent.TimeUnit;

import org.example.notificationservice.application.port.EventPublisherPort;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.example.notificationservice.domain.NewNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Publishes notification.requested keyed by tenantId and waits for the broker ack. */
@Component
@Slf4j
public class KafkaEventPublisher implements EventPublisherPort {

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final long timeoutMs;

    public KafkaEventPublisher(
            KafkaTemplate<String, Object> kafka,
            @Value("${app.kafka.topic.requested}") String topic,
            @Value("${notification.kafka.send-timeout-ms:5000}") long timeoutMs) {
        this.kafka = kafka;
        this.topic = topic;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void publishSync(NewNotification n) {
        try {
            kafka.send(topic, n.tenantId(), NotificationEvents.toRequestedEvent(n))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("kafka", e);
        } catch (Exception e) {
            throw new DependencyUnavailableException("kafka", e);
        }
    }
}
