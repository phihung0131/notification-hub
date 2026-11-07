package org.example.notificationservice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.example.notificationservice.application.StatusUpdateService.Change;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.domain.NotificationStateMachine;
import org.junit.jupiter.api.Test;

class StatusUpdateServiceTest {

    @Test
    void stateMachineNeverRegresses() {
        assertTrue(
                NotificationStateMachine.canTransition(
                        NotificationStatus.PENDING, NotificationStatus.SENT));
        assertTrue(
                NotificationStateMachine.canTransition(
                        NotificationStatus.PENDING, NotificationStatus.FAILED));
        assertTrue(
                NotificationStateMachine.canTransition(
                        NotificationStatus.FAILED, NotificationStatus.SENT));
        assertFalse(
                NotificationStateMachine.canTransition(
                        NotificationStatus.SENT, NotificationStatus.FAILED));
        assertFalse(
                NotificationStateMachine.canTransition(
                        NotificationStatus.SENT, NotificationStatus.PENDING));
        assertFalse(
                NotificationStateMachine.canTransition(
                        NotificationStatus.FAILED, NotificationStatus.PENDING));
        assertFalse(
                NotificationStateMachine.canTransition(
                        NotificationStatus.SENT, NotificationStatus.SENT));
    }

    @Test
    void duplicateAndOutOfOrderEventsAreHarmless() {
        Fakes.Status store = new Fakes.Status();
        UUID id = UUID.randomUUID();
        store.map.put(id, NotificationStatus.PENDING);
        StatusUpdateService svc = new StatusUpdateService(store);

        svc.apply(List.of(new Change(id, NotificationStatus.SENT)));
        svc.apply(List.of(new Change(id, NotificationStatus.SENT))); // duplicate
        svc.apply(List.of(new Change(id, NotificationStatus.FAILED))); // late, must not regress
        assertEquals(NotificationStatus.SENT, store.map.get(id));
    }

    @Test
    void failedThenSentByDlqReplayMovesForward() {
        Fakes.Status store = new Fakes.Status();
        UUID id = UUID.randomUUID();
        store.map.put(id, NotificationStatus.PENDING);
        StatusUpdateService svc = new StatusUpdateService(store);
        svc.apply(List.of(new Change(id, NotificationStatus.FAILED)));
        svc.apply(List.of(new Change(id, NotificationStatus.SENT)));
        assertEquals(NotificationStatus.SENT, store.map.get(id));
    }

    @Test
    void unknownRowIsRetriedNotAcked() {
        StatusUpdateService svc = new StatusUpdateService(new Fakes.Status());
        assertThrows(
                StatusUpdateService.NotYetPersistedException.class,
                () -> svc.apply(List.of(new Change(UUID.randomUUID(), NotificationStatus.SENT))));
    }
}
