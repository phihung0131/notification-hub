package org.example.notificationservice.application.port;

import java.util.UUID;

import org.example.notificationservice.common.enums.NotificationStatus;

public interface NotificationStatusPort {

    enum Result {
        UPDATED,
        /** Duplicate or would regress: ignored by the state machine. */
        IGNORED,
        NOT_FOUND
    }

    /** Atomic, state-machine-guarded transition (never regresses). */
    Result transition(UUID id, NotificationStatus to);
}
