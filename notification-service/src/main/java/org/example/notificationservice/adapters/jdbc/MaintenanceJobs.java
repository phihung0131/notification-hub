package org.example.notificationservice.adapters.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Housekeeping: expire persisted idempotency keys after 24h, drop published outbox rows. */
@Component
@Slf4j
public class MaintenanceJobs {

    private final JdbcTemplate jdbc;

    public MaintenanceJobs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The Redis key expires by TTL; this frees the DB unique key so the 24h window is the same on
     * both sides. The notification row itself is kept.
     */
    @Scheduled(fixedDelayString = "${notification.idempotency.cleanup-interval-ms:600000}")
    public void expireIdempotencyKeys() {
        try {
            int n =
                    jdbc.update(
                            "UPDATE notifications SET idempotency_key = NULL WHERE id IN ("
                                    + "SELECT id FROM notifications WHERE idempotency_key IS NOT NULL"
                                    + " AND created_at < now() - interval '24 hours' LIMIT 5000)");
            if (n > 0) {
                log.info("Expired {} idempotency keys older than 24h", n);
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency key cleanup failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${notification.outbox.cleanup-interval-ms:60000}")
    public void purgePublishedOutbox() {
        try {
            jdbc.update(
                    "DELETE FROM outbox_events WHERE id IN (SELECT id FROM outbox_events"
                            + " WHERE sent_at IS NOT NULL AND sent_at < now() - interval '1 hour'"
                            + " LIMIT 5000)");
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed: {}", e.toString());
        }
    }
}
