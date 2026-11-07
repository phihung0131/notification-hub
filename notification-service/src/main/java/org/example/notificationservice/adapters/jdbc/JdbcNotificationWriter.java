package org.example.notificationservice.adapters.jdbc;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.example.notificationservice.application.port.NotificationWritePort;
import org.example.notificationservice.domain.DuplicateIdempotencyKeyException;
import org.example.notificationservice.domain.NewNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PostgreSQL adapter for the write path. The unique index on (tenant_id, idempotency_key) is the
 * final idempotency arbiter. {@code @Transactional} is on this bean and invoked from another bean,
 * so the proxy applies (no self-invocation).
 */
@Component
public class JdbcNotificationWriter implements NotificationWritePort {

    static final String INSERT_NOTIFICATION =
            "INSERT INTO notifications (id, tenant_id, recipient, subject, content, status,"
                    + " channel_id, created_at, updated_at, idempotency_key, request_fingerprint,"
                    + " priority) SELECT ?, ?, ?, ?, ?, 'PENDING', c.id, ?, ?, ?, ?, ?"
                    + " FROM channels c WHERE c.code = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String requestedTopic;

    public JdbcNotificationWriter(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.kafka.topic.requested}") String requestedTopic) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.requestedTopic = requestedTopic;
    }

    @Override
    @Transactional
    public void insertWithOutbox(NewNotification n) {
        int inserted;
        try {
            inserted = insert(n, INSERT_NOTIFICATION);
        } catch (DuplicateKeyException e) {
            if (n.idempotencyKey() != null) {
                throw new DuplicateIdempotencyKeyException(
                        "duplicate idempotency key " + n.idempotencyKey(), e);
            }
            throw e;
        }
        if (inserted != 1) {
            throw new IllegalStateException("Channel not found: " + n.channelCode());
        }
        jdbc.update(
                "INSERT INTO outbox_events (id, aggregate_id, tenant_id, topic, event_key,"
                        + " payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                n.id(),
                n.tenantId(),
                requestedTopic,
                n.tenantId(),
                toJson(n),
                Timestamp.from(n.createdAt()));
    }

    /**
     * Insert from a consumed notification.requested event (kafka-first). Idempotent: duplicates
     * (same id or same idempotency key) are skipped. Returns true when a row was created.
     */
    @Transactional
    public boolean insertIfAbsent(NewNotification n) {
        return insert(n, INSERT_NOTIFICATION + " ON CONFLICT DO NOTHING") == 1;
    }

    private int insert(NewNotification n, String sql) {
        return jdbc.update(
                sql,
                n.id(),
                n.tenantId(),
                n.recipient(),
                n.subject(),
                n.content(),
                Timestamp.from(n.createdAt()),
                Timestamp.from(n.createdAt()),
                n.idempotencyKey(),
                n.fingerprint(),
                n.priority(),
                n.channelCode());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Existing> findByIdempotencyKey(String tenantId, String key) {
        return jdbc
                .query(
                        "SELECT id, request_fingerprint FROM notifications"
                                + " WHERE tenant_id = ? AND idempotency_key = ?",
                        (rs, i) -> new Existing(rs.getString(1), rs.getString(2)),
                        tenantId,
                        key)
                .stream()
                .findFirst();
    }

    private String toJson(NewNotification n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
