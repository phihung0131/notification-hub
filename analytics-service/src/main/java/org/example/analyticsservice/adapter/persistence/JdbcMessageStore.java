package org.example.analyticsservice.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.example.analyticsservice.application.port.MessageReadPort;
import org.example.analyticsservice.application.port.MessageWritePort;
import org.example.analyticsservice.domain.MessagePage;
import org.example.analyticsservice.domain.MessageRecord;
import org.example.analyticsservice.domain.MessageSearchCriteria;
import org.example.analyticsservice.domain.MessageStatusProgression;
import org.example.events.enums.NotificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * PostgreSQL adapter. Writes use {@code INSERT .. ON CONFLICT DO UPDATE .. WHERE guard} so the
 * database, not the JVM, arbitrates ordering: duplicates and late events are no-ops.
 */
@Repository
@RequiredArgsConstructor
public class JdbcMessageStore implements MessageWritePort, MessageReadPort {

    /**
     * Guard: status advances only to a strictly higher rank and never leaves FAILED. Null fields
     * are back-filled (a result event may have created the row first). The WHERE clause skips the
     * write entirely when nothing would change.
     */
    static final String UPSERT_SQL =
            """
            INSERT INTO messages (message_id, tenant_id, channel, recipient, subject, content,
                                  status, status_rank, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO UPDATE SET
                channel     = COALESCE(messages.channel, EXCLUDED.channel),
                recipient   = COALESCE(messages.recipient, EXCLUDED.recipient),
                subject     = COALESCE(messages.subject, EXCLUDED.subject),
                content     = COALESCE(messages.content, EXCLUDED.content),
                status      = CASE WHEN messages.status <> 'FAILED'
                                    AND messages.status_rank < EXCLUDED.status_rank
                               THEN EXCLUDED.status ELSE messages.status END,
                status_rank = CASE WHEN messages.status <> 'FAILED'
                                    AND messages.status_rank < EXCLUDED.status_rank
                               THEN EXCLUDED.status_rank ELSE messages.status_rank END,
                created_at  = LEAST(messages.created_at, EXCLUDED.created_at),
                updated_at  = EXCLUDED.updated_at
            WHERE (messages.status <> 'FAILED' AND messages.status_rank < EXCLUDED.status_rank)
               OR (messages.channel IS NULL AND EXCLUDED.channel IS NOT NULL)
               OR (messages.recipient IS NULL AND EXCLUDED.recipient IS NOT NULL)
               OR (messages.subject IS NULL AND EXCLUDED.subject IS NOT NULL)
               OR (messages.content IS NULL AND EXCLUDED.content IS NOT NULL)
               OR messages.created_at > EXCLUDED.created_at
            """;

    private static final String COLUMNS =
            "message_id, tenant_id, channel, recipient, subject, content, status, created_at,"
                    + " updated_at";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    @Override
    @Transactional
    public void upsertAll(List<MessageRecord> records) {
        jdbc.batchUpdate(
                UPSERT_SQL,
                records,
                records.size(),
                (ps, r) -> {
                    ps.setObject(1, r.messageId());
                    ps.setString(2, r.tenantId());
                    ps.setString(3, r.channel());
                    ps.setString(4, r.recipient());
                    ps.setString(5, r.subject());
                    ps.setString(6, r.content());
                    ps.setString(7, r.status().name());
                    ps.setInt(8, MessageStatusProgression.rank(r.status()));
                    ps.setTimestamp(9, Timestamp.from(r.createdAt()));
                    ps.setTimestamp(10, Timestamp.from(r.updatedAt()));
                });
    }

    @Override
    public Optional<MessageRecord> findById(UUID id) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM messages WHERE message_id = ?",
                        JdbcMessageStore::map,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public MessagePage search(MessageSearchCriteria c, String cursor, int limit) {
        StringBuilder sql =
                new StringBuilder("SELECT " + COLUMNS + " FROM messages WHERE tenant_id = :tenant");
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", c.tenantId());
        appendFilters(sql, p, c.status(), c.channel(), c.from(), c.to());
        if (cursor != null && !cursor.isBlank()) {
            Cursor cur = Cursor.decode(cursor);
            sql.append(" AND (created_at, message_id) < (:curTs, :curId)");
            p.addValue("curTs", Timestamp.from(cur.createdAt()));
            p.addValue("curId", cur.id(), Types.OTHER);
        }
        sql.append(" ORDER BY created_at DESC, message_id DESC LIMIT :limit");
        p.addValue("limit", limit + 1);

        List<MessageRecord> rows = named.query(sql.toString(), p, JdbcMessageStore::map);
        if (rows.size() <= limit) {
            return new MessagePage(rows, null);
        }
        List<MessageRecord> items = rows.subList(0, limit);
        MessageRecord last = items.get(limit - 1);
        return new MessagePage(
                List.copyOf(items), new Cursor(last.createdAt(), last.messageId()).encode());
    }

    @Override
    public Map<NotificationStatus, Long> countByStatus(String tenantId, Instant from, Instant to) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT status, COUNT(*) AS n FROM messages WHERE tenant_id = :tenant");
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", tenantId);
        appendFilters(sql, p, null, null, from, to);
        sql.append(" GROUP BY status");
        Map<NotificationStatus, Long> out = new EnumMap<>(NotificationStatus.class);
        named.query(
                sql.toString(),
                p,
                rs -> {
                    out.put(NotificationStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        return out;
    }

    private static void appendFilters(
            StringBuilder sql,
            MapSqlParameterSource p,
            NotificationStatus status,
            String channel,
            Instant from,
            Instant to) {
        if (status != null) {
            sql.append(" AND status = :status");
            p.addValue("status", status.name());
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel = :channel");
            p.addValue("channel", channel);
        }
        if (from != null) {
            sql.append(" AND created_at >= :from");
            p.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at < :to");
            p.addValue("to", Timestamp.from(to));
        }
    }

    private static MessageRecord map(ResultSet rs, int i) throws SQLException {
        return new MessageRecord(
                rs.getObject("message_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("channel"),
                rs.getString("recipient"),
                rs.getString("subject"),
                rs.getString("content"),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** Opaque keyset cursor: base64("epochMicros|uuid"). */
    record Cursor(Instant createdAt, UUID id) {
        String encode() {
            long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString((micros + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                long micros = Long.parseLong(raw.substring(0, sep));
                Instant ts =
                        Instant.ofEpochSecond(
                                Math.floorDiv(micros, 1_000_000L),
                                Math.floorMod(micros, 1_000_000L) * 1_000L);
                return new Cursor(ts, UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
