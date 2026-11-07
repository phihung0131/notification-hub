package org.example.deliveryservice.kafka.consumer;

import java.time.Clock;
import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.lane.Lane;
import org.example.deliveryservice.service.DeliveryProcessor;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Consumes the delay-tier topics. A record is held (only this listener's thread waits, never the
 * lane threads) until record-timestamp + tier delay, then processed by the same idempotent code.
 */
@Component
@Slf4j
public class RetryTierConsumer {

    private final DeliveryProcessor processor;
    private final DeliveryProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RetryTierConsumer(DeliveryProcessor processor, DeliveryProperties props) {
        this(processor, props, Clock.systemUTC());
    }

    public RetryTierConsumer(DeliveryProcessor processor, DeliveryProperties props, Clock clock) {
        this.processor = processor;
        this.props = props;
        this.clock = clock;
    }

    @KafkaListener(
            id = "retry-tiers",
            topics = "#{@deliveryProperties.retryTopicNames()}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "retryListenerFactory",
            concurrency = "${app.delivery.retry.concurrency}")
    public void consume(ConsumerRecord<String, NotificationEvent> record, Acknowledgment ack) {
        long remaining = remainingDelayMs(record);
        if (remaining > 0) {
            // not due yet: redeliver after the remaining delay (does not touch other consumers)
            ack.nack(Duration.ofMillis(remaining));
            return;
        }
        processor.process(record.value(), Lane.fromHeaders(record.headers()).label());
        ack.acknowledge();
    }

    long remainingDelayMs(ConsumerRecord<?, ?> record) {
        Duration delay = props.getRetry().delayFor(record.topic());
        return record.timestamp() + delay.toMillis() - clock.millis();
    }
}
