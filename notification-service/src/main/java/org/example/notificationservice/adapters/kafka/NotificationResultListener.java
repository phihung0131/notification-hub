package org.example.notificationservice.adapters.kafka;

import java.util.ArrayList;
import java.util.List;

import org.example.events.NotificationEvent;
import org.example.notificationservice.application.StatusUpdateService;
import org.example.notificationservice.application.StatusUpdateService.Change;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Updates Notification.status from notification.result. Offsets are acknowledged only after the DB
 * update returned; the state machine makes redelivery and reordering harmless.
 */
@Component
@RequiredArgsConstructor
public class NotificationResultListener {

    private final StatusUpdateService statusUpdateService;

    @KafkaListener(
            topics = "${app.kafka.topic.result}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "resultListenerFactory")
    public void onResults(List<NotificationEvent> batch, Acknowledgment ack) {
        List<Change> changes = new ArrayList<>(batch.size());
        for (NotificationEvent e : batch) {
            if (e == null || e.getId() == null || e.getStatus() == null) {
                continue;
            }
            switch (e.getStatus()) {
                case SENT -> changes.add(new Change(e.getId(), NotificationStatus.SENT));
                case FAILED -> changes.add(new Change(e.getId(), NotificationStatus.FAILED));
                default -> {}
            }
        }
        statusUpdateService.apply(changes);
        ack.acknowledge();
    }
}
