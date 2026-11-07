package org.example.analyticsservice.adapter.kafka;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.analyticsservice.application.MessageIngestService;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Batch listeners for the requested and result topics. Offsets are acknowledged only after the
 * batch is durably upserted; any exception skips the ack and the error handler redelivers the same
 * batch (see KafkaConsumerConfig). No in-memory buffering, so a crash loses nothing.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventBatchConsumer {

    private final MessageIngestService ingestService;

    @KafkaListener(
            id = "analytics-requested",
            topics = "${app.kafka.topic.requested}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onRequested(
            List<ConsumerRecord<String, NotificationEvent>> records, Acknowledgment ack) {
        handle(records, ack);
    }

    @KafkaListener(
            id = "analytics-result",
            topics = "${app.kafka.topic.result}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onResult(
            List<ConsumerRecord<String, NotificationEvent>> records, Acknowledgment ack) {
        handle(records, ack);
    }

    void handle(List<ConsumerRecord<String, NotificationEvent>> records, Acknowledgment ack) {
        ingestService.ingest(records.stream().map(ConsumerRecord::value).toList());
        ack.acknowledge();
    }
}
