package org.example.notificationservice.adapters.kafka;

import java.util.List;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.notificationservice.adapters.jdbc.JdbcNotificationWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * kafka-first mode only: creates the notifications row from notification.requested. Idempotent
 * (INSERT .. ON CONFLICT DO NOTHING), acknowledged after the DB write.
 */
@Component
@ConditionalOnProperty(name = "notification.write-mode", havingValue = "kafka-first")
@RequiredArgsConstructor
@Slf4j
public class NotificationWriterListener {

    private final JdbcNotificationWriter writer;

    @KafkaListener(
            topics = "${app.kafka.topic.requested}",
            groupId = "${spring.kafka.consumer.group-id}-writer",
            containerFactory = "writerListenerFactory")
    public void onRequested(List<NotificationEvent> batch, Acknowledgment ack) {
        for (NotificationEvent e : batch) {
            if (e == null || e.getId() == null || e.getStatus() != NotificationStatus.PENDING) {
                continue;
            }
            boolean created = writer.insertIfAbsent(NotificationEvents.fromRequestedEvent(e));
            if (!created) {
                log.debug("Notification {} already persisted, skipping", e.getId());
            }
        }
        ack.acknowledge();
    }
}
