package org.example.notificationservice.application;

import org.example.notificationservice.application.port.EventPublisherPort;
import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.domain.WriteMode;

/** Kafka (acks=all) is the first durable write; a consumer inserts the DB row (ADR 0005). */
public class KafkaFirstWritePath implements WritePath {

    private final EventPublisherPort publisher;

    public KafkaFirstWritePath(EventPublisherPort publisher) {
        this.publisher = publisher;
    }

    @Override
    public WriteMode mode() {
        return WriteMode.KAFKA_FIRST;
    }

    @Override
    public void write(NewNotification notification) {
        publisher.publishSync(notification);
    }

    @Override
    public boolean redisIsSoleIdempotencyArbiter() {
        return true;
    }
}
