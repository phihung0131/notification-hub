package org.example.notificationservice.application;

import org.example.notificationservice.application.port.NotificationWritePort;
import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.domain.WriteMode;

/** notification + outbox row in one DB transaction; a relay publishes to Kafka (ADR 0005). */
public class OutboxWritePath implements WritePath {

    private final NotificationWritePort writePort;

    public OutboxWritePath(NotificationWritePort writePort) {
        this.writePort = writePort;
    }

    @Override
    public WriteMode mode() {
        return WriteMode.OUTBOX;
    }

    @Override
    public void write(NewNotification notification) {
        writePort.insertWithOutbox(notification);
    }

    @Override
    public boolean redisIsSoleIdempotencyArbiter() {
        return false;
    }
}
