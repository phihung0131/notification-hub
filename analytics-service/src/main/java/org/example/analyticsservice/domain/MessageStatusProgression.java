package org.example.analyticsservice.domain;

import org.example.events.enums.NotificationStatus;

/**
 * Forward-only status state machine. Ranks: PENDING(0) &lt; SENT(1) &lt; FAILED(2) &lt; READ(3)
 * &lt; ACKNOWLEDGED(4). A status only moves to a strictly higher rank. FAILED is terminal: nothing
 * replaces it. The same rule is enforced in SQL by the upsert guard (see ADR 0009).
 */
public final class MessageStatusProgression {

    private MessageStatusProgression() {}

    public static int rank(NotificationStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case PENDING -> 0;
            case SENT -> 1;
            case FAILED -> 2;
            case READ -> 3;
            case ACKNOWLEDGED -> 4;
        };
    }

    public static boolean canTransition(NotificationStatus from, NotificationStatus to) {
        if (from == NotificationStatus.FAILED) {
            return false;
        }
        return rank(to) > rank(from);
    }

    /** Returns the status that wins when {@code incoming} is applied on top of {@code current}. */
    public static NotificationStatus resolve(
            NotificationStatus current, NotificationStatus incoming) {
        if (current == null) {
            return incoming == null ? NotificationStatus.PENDING : incoming;
        }
        return canTransition(current, incoming) ? incoming : current;
    }
}
