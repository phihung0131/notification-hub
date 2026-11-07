package org.example.analyticsservice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.analyticsservice.application.port.MessageWritePort;
import org.example.analyticsservice.domain.MessageRecord;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Uses an in-memory port that applies the same merge semantics the SQL upsert guard implements
 * (MessageRecord.mergeWith), to exercise ordering, duplicate and regression cases.
 */
class MessageIngestServiceTest {

    static class InMemoryStore implements MessageWritePort {
        final Map<UUID, MessageRecord> rows = new HashMap<>();
        final List<Integer> batchSizes = new ArrayList<>();
        boolean failNext;

        @Override
        public void upsertAll(List<MessageRecord> records) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("db down");
            }
            batchSizes.add(records.size());
            for (MessageRecord r : records) {
                rows.merge(r.messageId(), r, (existing, incoming) -> existing.mergeWith(incoming));
            }
        }
    }

    private InMemoryStore store;
    private MessageIngestService service;
    private UUID id;
    private Instant t0;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new MessageIngestService(store);
        id = UUID.randomUUID();
        t0 = Instant.parse("2026-01-01T00:00:00Z");
    }

    private NotificationEvent event(UUID eventId, NotificationStatus status, Instant created) {
        return NotificationEvent.newBuilder()
                .setId(eventId)
                .setTenantId("t1")
                .setChannel("email")
                .setRecipient("a@b.c")
                .setSubject("s")
                .setContent("c")
                .setStatus(status)
                .setCreatedAt(created)
                .build();
    }

    @Test
    void inOrderRequestedThenResult() {
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        service.ingest(List.of(event(id, NotificationStatus.SENT, t0)));
        assertEquals(NotificationStatus.SENT, store.rows.get(id).status());
    }

    @Test
    void lateRequestedDoesNotRegressSent_acrossBatches() {
        service.ingest(List.of(event(id, NotificationStatus.SENT, t0)));
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        assertEquals(NotificationStatus.SENT, store.rows.get(id).status());
    }

    @Test
    void lateRequestedDoesNotRegressSent_sameBatchAnyOrder() {
        service.ingest(
                List.of(
                        event(id, NotificationStatus.SENT, t0),
                        event(id, NotificationStatus.PENDING, t0)));
        assertEquals(NotificationStatus.SENT, store.rows.get(id).status());
        assertEquals(List.of(1), store.batchSizes, "same id folded into one upsert row");
    }

    @Test
    void duplicatesAreIdempotent() {
        NotificationEvent e = event(id, NotificationStatus.SENT, t0);
        service.ingest(List.of(e, e, e));
        service.ingest(List.of(e));
        assertEquals(1, store.rows.size());
        assertEquals(NotificationStatus.SENT, store.rows.get(id).status());
    }

    @Test
    void failedStaysFailed() {
        service.ingest(List.of(event(id, NotificationStatus.FAILED, t0)));
        service.ingest(List.of(event(id, NotificationStatus.SENT, t0)));
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        assertEquals(NotificationStatus.FAILED, store.rows.get(id).status());
    }

    @Test
    void earliestCreatedAtWins() {
        service.ingest(List.of(event(id, NotificationStatus.SENT, t0.plusSeconds(5))));
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        assertEquals(t0, store.rows.get(id).createdAt());
    }

    @Test
    void nullFieldsAreBackfilled() {
        NotificationEvent sparse =
                NotificationEvent.newBuilder()
                        .setId(id)
                        .setTenantId("t1")
                        .setStatus(NotificationStatus.SENT)
                        .build();
        service.ingest(List.of(sparse));
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        MessageRecord row = store.rows.get(id);
        assertEquals("email", row.channel());
        assertEquals(NotificationStatus.SENT, row.status());
    }

    @Test
    void eventsWithoutIdOrTenantAreDroppedNotFatal() {
        NotificationEvent noId =
                NotificationEvent.newBuilder()
                        .setTenantId("t1")
                        .setStatus(NotificationStatus.SENT)
                        .build();
        service.ingest(List.of(noId, event(id, NotificationStatus.PENDING, t0)));
        assertEquals(1, store.rows.size());
    }

    @Test
    void dbFailurePropagatesSoCallerDoesNotAck() {
        store.failNext = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.ingest(List.of(event(id, NotificationStatus.PENDING, t0))));
        // redelivery of the same batch succeeds, nothing lost
        service.ingest(List.of(event(id, NotificationStatus.PENDING, t0)));
        assertTrue(store.rows.containsKey(id));
    }
}
