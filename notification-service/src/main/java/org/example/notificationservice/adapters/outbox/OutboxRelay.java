package org.example.notificationservice.adapters.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.example.notificationservice.adapters.kafka.NotificationEvents;
import org.example.notificationservice.domain.NewNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbox relay (outbox mode). One poll = one DB transaction: lock a batch with FOR UPDATE SKIP
 * LOCKED (several instances never take the same rows), publish the batch (producer acks=all +
 * idempotence), wait for every ack, mark rows sent, commit. Any failure rolls back, so rows are
 * retried: delivery to Kafka is at-least-once and consumers deduplicate by notification id.
 * Messages are keyed by tenantId (per-tenant ordering within a partition).
 */
@Component
@ConditionalOnProperty(
        name = "notification.write-mode",
        havingValue = "outbox",
        matchIfMissing = true)
@Slf4j
public class OutboxRelay {

    private record Row(UUID id, String topic, String key, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper mapper;
    private final int batchSize;
    private final long ackTimeoutMs;

    public OutboxRelay(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            KafkaTemplate<String, Object> kafka,
            ObjectMapper mapper,
            @Value("${notification.outbox.batch-size:200}") int batchSize,
            @Value("${notification.outbox.ack-timeout-ms:10000}") long ackTimeoutMs) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.mapper = mapper;
        this.batchSize = batchSize;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval-ms:100}")
    public void poll() {
        try {
            int n;
            do {
                n = pollOnce();
            } while (n >= batchSize); // drain a backlog without waiting for the next tick
        } catch (Exception e) {
            log.warn("Outbox relay poll failed, will retry: {}", e.toString());
        }
    }

    /**
     * @return number of rows published
     */
    public int pollOnce() {
        Integer published =
                tx.execute(
                        status -> {
                            List<Row> rows =
                                    jdbc.query(
                                            "SELECT id, topic, event_key, payload FROM outbox_events"
                                                    + " WHERE sent_at IS NULL ORDER BY created_at, id"
                                                    + " LIMIT ? FOR UPDATE SKIP LOCKED",
                                            (rs, i) ->
                                                    new Row(
                                                            rs.getObject(1, UUID.class),
                                                            rs.getString(2),
                                                            rs.getString(3),
                                                            rs.getString(4)),
                                            batchSize);
                            if (rows.isEmpty()) {
                                return 0;
                            }
                            List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
                            for (Row r : rows) {
                                acks.add(kafka.send(r.topic(), r.key(), toEvent(r.payload())));
                            }
                            try {
                                CompletableFuture.allOf(acks.toArray(new CompletableFuture[0]))
                                        .get(ackTimeoutMs, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ie);
                            } catch (Exception e) {
                                throw new IllegalStateException("Kafka publish failed", e);
                            }
                            jdbc.batchUpdate(
                                    "UPDATE outbox_events SET sent_at = now() WHERE id = ?",
                                    rows.stream().map(r -> new Object[] {r.id()}).toList());
                            return rows.size();
                        });
        return published == null ? 0 : published;
    }

    private Object toEvent(String payload) {
        try {
            NewNotification n = mapper.readValue(payload, NewNotification.class);
            return NotificationEvents.toRequestedEvent(n);
        } catch (Exception e) {
            throw new IllegalStateException("Bad outbox payload", e);
        }
    }
}
