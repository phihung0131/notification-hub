package org.example.analyticsservice.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.Test;

class MessageStatusProgressionTest {

    @Test
    void forwardTransitionsAllowed() {
        assertTrue(
                MessageStatusProgression.canTransition(
                        NotificationStatus.PENDING, NotificationStatus.SENT));
        assertTrue(
                MessageStatusProgression.canTransition(
                        NotificationStatus.SENT, NotificationStatus.READ));
        assertTrue(
                MessageStatusProgression.canTransition(
                        NotificationStatus.SENT, NotificationStatus.FAILED));
    }

    @Test
    void lateRequestedNeverRegressesSent() {
        assertEquals(
                NotificationStatus.SENT,
                MessageStatusProgression.resolve(
                        NotificationStatus.SENT, NotificationStatus.PENDING));
    }

    @Test
    void sameStatusIsNoOp() {
        assertFalse(
                MessageStatusProgression.canTransition(
                        NotificationStatus.SENT, NotificationStatus.SENT));
    }

    @Test
    void failedIsTerminal() {
        for (NotificationStatus s : NotificationStatus.values()) {
            assertEquals(
                    NotificationStatus.FAILED,
                    MessageStatusProgression.resolve(NotificationStatus.FAILED, s));
        }
    }

    @Test
    void failedNeverOverwritesReadOrAck() {
        assertEquals(
                NotificationStatus.READ,
                MessageStatusProgression.resolve(
                        NotificationStatus.READ, NotificationStatus.FAILED));
    }
}
