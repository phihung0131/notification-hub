package org.example.analyticsservice.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JdbcMessageStoreTest {

    @Test
    void cursorRoundTripsMicrosecondPrecision() {
        Instant ts = Instant.parse("2026-03-04T05:06:07.123456Z");
        UUID id = UUID.randomUUID();
        JdbcMessageStore.Cursor decoded =
                JdbcMessageStore.Cursor.decode(new JdbcMessageStore.Cursor(ts, id).encode());
        assertEquals(ts, decoded.createdAt());
        assertEquals(id, decoded.id());
    }

    @Test
    void garbageCursorRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JdbcMessageStore.Cursor.decode("not-a-cursor"));
    }

    @Test
    void upsertSqlCarriesStatusGuard() {
        // Guard against accidental edits: forward-only rank check + FAILED terminal rule.
        assertTrue(
                JdbcMessageStore.UPSERT_SQL.contains(
                        "messages.status_rank < EXCLUDED.status_rank"));
        assertTrue(JdbcMessageStore.UPSERT_SQL.contains("messages.status <> 'FAILED'"));
        assertTrue(JdbcMessageStore.UPSERT_SQL.contains("ON CONFLICT (message_id) DO UPDATE"));
    }
}
