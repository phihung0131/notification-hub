package org.example.notificationservice.domain;

/** How an accepted notification becomes durable + published. See ADR 0005. */
public enum WriteMode {
    /** One DB transaction writes notification + outbox row; a relay publishes to Kafka. */
    OUTBOX,
    /** Kafka (acks=all) is the first durable write; a consumer creates the DB row. */
    KAFKA_FIRST;

    public static WriteMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OUTBOX;
        }
        return valueOf(value.trim().replace('-', '_').toUpperCase());
    }
}
