package org.example.notificationservice.application;

import java.util.List;
import java.util.UUID;

import org.example.notificationservice.application.port.NotificationStatusPort;
import org.example.notificationservice.application.port.NotificationStatusPort.Result;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies notification.result events to Notification.status through the state machine. Idempotent
 * and order-tolerant: duplicates and regressions are ignored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatusUpdateService {

    public record Change(UUID id, NotificationStatus status) {}

    /** The row does not exist (yet): the caller should retry later (kafka-first lag). */
    public static class NotYetPersistedException extends RuntimeException {
        public NotYetPersistedException(UUID id) {
            super("Notification not yet persisted: " + id);
        }
    }

    private final NotificationStatusPort statusPort;

    public void apply(List<Change> changes) {
        UUID missing = null;
        for (Change c : changes) {
            Result r = statusPort.transition(c.id(), c.status());
            if (r == Result.NOT_FOUND) {
                missing = c.id();
            }
        }
        if (missing != null) {
            throw new NotYetPersistedException(missing);
        }
    }
}
