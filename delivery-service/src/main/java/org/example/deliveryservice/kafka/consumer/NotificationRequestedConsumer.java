package org.example.deliveryservice.kafka.consumer;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.example.deliveryservice.kafka.lane.Lane;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.service.DeliveryProcessor;
import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Weighted priority lanes: one listener per lane topic, each with its own thread count and poll
 * size, so a flood on low/normal can never occupy the threads that serve high (OTP). Concurrency is
 * capped by the partition count of the lane topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestedConsumer {

    static final String H_ROUTED = "x-routed";

    private final DeliveryProcessor processor;
    private final KafkaProducerService producer;

    @Value("${app.kafka.topic.requested-high}")
    private String highTopic;

    @Value("${app.kafka.topic.requested-low}")
    private String lowTopic;

    @KafkaListener(
            id = "lane-high",
            topics = "${app.kafka.topic.requested-high}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "laneListenerFactory",
            concurrency = "${app.delivery.lanes.high.concurrency}",
            properties = "max.poll.records=${app.delivery.lanes.high.max-poll-records}")
    public void high(ConsumerRecord<String, NotificationEvent> record) {
        processor.process(record.value(), Lane.HIGH.label());
    }

    @KafkaListener(
            id = "lane-normal",
            topics = "${app.kafka.topic.requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "laneListenerFactory",
            concurrency = "${app.delivery.lanes.normal.concurrency}",
            properties = "max.poll.records=${app.delivery.lanes.normal.max-poll-records}")
    public void normal(ConsumerRecord<String, NotificationEvent> record) {
        // Ingest publishes to the default topic; a priority header moves it to its own lane.
        Lane lane = Lane.fromHeaders(record.headers());
        if (lane != Lane.NORMAL && record.headers().lastHeader(H_ROUTED) == null) {
            ProducerRecord<String, Object> fwd =
                    new ProducerRecord<>(
                            lane == Lane.HIGH ? highTopic : lowTopic,
                            null,
                            record.key(),
                            record.value(),
                            record.headers());
            fwd.headers().add(H_ROUTED, "1".getBytes(StandardCharsets.UTF_8));
            producer.send(fwd);
            log.debug("Routed {} to {} lane", record.key(), lane);
            return;
        }
        processor.process(record.value(), Lane.NORMAL.label());
    }

    @KafkaListener(
            id = "lane-low",
            topics = "${app.kafka.topic.requested-low}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "laneListenerFactory",
            concurrency = "${app.delivery.lanes.low.concurrency}",
            properties = "max.poll.records=${app.delivery.lanes.low.max-poll-records}")
    public void low(ConsumerRecord<String, NotificationEvent> record) {
        processor.process(record.value(), Lane.LOW.label());
    }
}
