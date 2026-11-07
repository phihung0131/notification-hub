package org.example.tenantservice.quota.adapters.kafka;

import java.util.ArrayList;
import java.util.List;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.example.tenantservice.quota.application.ResultEventProcessor;
import org.example.tenantservice.quota.domain.ResultEvent;
import org.example.tenantservice.quota.domain.ResultEvent.Outcome;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound Kafka adapter for notification results. The offset is committed ONLY after {@link
 * ResultEventProcessor#process} returned, i.e. after the durable DB write. Any exception skips the
 * ack and the container redelivers the batch (idempotent downstream).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationResultConsumer {

    private final ResultEventProcessor processor;

    @KafkaListener(
            topics = "${app.kafka.topic.result}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(List<NotificationEvent> batch, Acknowledgment acknowledgment) {
        List<ResultEvent> events = new ArrayList<>(batch.size());
        for (NotificationEvent e : batch) {
            ResultEvent mapped = map(e);
            if (mapped != null) {
                events.add(mapped);
            }
        }
        processor.process(events);
        acknowledgment.acknowledge();
    }

    static ResultEvent map(NotificationEvent e) {
        if (e == null || e.getId() == null || e.getTenantId() == null || e.getStatus() == null) {
            return null; // poison / undeserializable record: skip, never block the partition
        }
        if (e.getStatus() == NotificationStatus.SENT) {
            return new ResultEvent(e.getId().toString(), e.getTenantId(), Outcome.SENT);
        }
        if (e.getStatus() == NotificationStatus.FAILED) {
            return new ResultEvent(e.getId().toString(), e.getTenantId(), Outcome.FAILED);
        }
        return null; // PENDING / READ / ACKNOWLEDGED do not affect quota
    }
}
