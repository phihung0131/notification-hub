package org.example.notificationservice.domain;

import java.util.EnumSet;
import java.util.Set;

import org.example.notificationservice.common.enums.NotificationStatus;

/**
 * Status transitions that never regress: PENDING -> SENT | FAILED, FAILED -> SENT (a DLQ replay may
 * later succeed). SENT is final; nothing returns to PENDING.
 */
public final class NotificationStateMachine {

    private NotificationStateMachine() {}

    public static boolean canTransition(NotificationStatus from, NotificationStatus to) {
        return allowedSources(to).contains(from);
    }

    /** Statuses from which a move to {@code to} is legal. */
    public static Set<NotificationStatus> allowedSources(NotificationStatus to) {
        return switch (to) {
            case SENT -> EnumSet.of(NotificationStatus.PENDING, NotificationStatus.FAILED);
            case FAILED -> EnumSet.of(NotificationStatus.PENDING);
            case PENDING -> EnumSet.noneOf(NotificationStatus.class);
        };
    }
}
