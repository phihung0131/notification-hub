package org.example.notificationservice.application;

import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.domain.WriteMode;

/** Strategy for making an accepted notification durable and visible to Kafka consumers. */
public interface WritePath {

    WriteMode mode();

    /**
     * Make the notification durable. After this returns, the 202 may be sent.
     *
     * @throws org.example.notificationservice.domain.DuplicateIdempotencyKeyException if the DB
     *     unique key rejected it (outbox mode)
     * @throws org.example.notificationservice.domain.DependencyUnavailableException if the sink is
     *     down
     */
    void write(NewNotification notification);

    /**
     * True when Redis is the only idempotency arbiter in this mode. Then an unreachable Redis must
     * fail the request (503); otherwise the DB unique constraint covers it.
     */
    boolean redisIsSoleIdempotencyArbiter();
}
