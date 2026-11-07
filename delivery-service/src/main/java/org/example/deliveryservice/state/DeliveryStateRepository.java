package org.example.deliveryservice.state;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** Postgres-backed processed-id store, state machine and webhook dedupe. */
@Repository
@RequiredArgsConstructor
public class DeliveryStateRepository {

    private final JdbcTemplate jdbc;

    public record Row(
            UUID notificationId,
            String tenantId,
            String channel,
            String recipient,
            DeliveryState state,
            boolean resultPublished) {}

    /** Unique insert; false when the notification was already claimed (redelivery). */
    public boolean insertIfAbsent(UUID id, String tenant, String channel, String recipient) {
        return jdbc.update(
                        "INSERT INTO notification_delivery(notification_id, tenant_id, channel,"
                                + " recipient) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                        id,
                        tenant,
                        channel,
                        recipient)
                == 1;
    }

    public Optional<Row> find(UUID id) {
        return jdbc
                .query(
                        "SELECT notification_id, tenant_id, channel, recipient, status,"
                                + " result_published FROM notification_delivery WHERE"
                                + " notification_id = ?",
                        (rs, i) ->
                                new Row(
                                        rs.getObject("notification_id", UUID.class),
                                        rs.getString("tenant_id"),
                                        rs.getString("channel"),
                                        rs.getString("recipient"),
                                        DeliveryState.valueOf(rs.getString("status")),
                                        rs.getBoolean("result_published")),
                        id)
                .stream()
                .findFirst();
    }

    /** Move forward only (status_rank guard). Returns true when the row changed. */
    public boolean advance(UUID id, DeliveryState to, String providerMessageId) {
        return jdbc.update(
                        "UPDATE notification_delivery SET status = ?, status_rank = ?,"
                                + " provider_message_id = COALESCE(?, provider_message_id),"
                                + " updated_at = now() WHERE notification_id = ? AND status_rank <"
                                + " ?",
                        to.name(),
                        to.rank(),
                        providerMessageId,
                        id,
                        to.rank())
                == 1;
    }

    /** Terminal failure, only legal while nothing was sent yet. */
    public boolean failIfInProgress(UUID id, String reason) {
        return jdbc.update(
                        "UPDATE notification_delivery SET status = 'FAILED', status_rank = 2,"
                                + " failure_reason = ?, updated_at = now() WHERE notification_id ="
                                + " ? AND status = 'IN_PROGRESS'",
                        reason,
                        id)
                == 1;
    }

    public void markResultPublished(UUID id) {
        jdbc.update(
                "UPDATE notification_delivery SET result_published = TRUE, updated_at = now()"
                        + " WHERE notification_id = ?",
                id);
    }

    /** Re-arm a dead-lettered notification for replay. Only FAILED rows are reset. */
    public boolean resetFailed(UUID id) {
        return jdbc.update(
                        "UPDATE notification_delivery SET status = 'IN_PROGRESS', status_rank = 0,"
                                + " result_published = FALSE, failure_reason = NULL, updated_at ="
                                + " now() WHERE notification_id = ? AND status = 'FAILED'",
                        id)
                == 1;
    }

    /** Unique insert of a provider event id; false = duplicate callback. */
    public boolean recordProviderEvent(String eventId, UUID notificationId) {
        return jdbc.update(
                        "INSERT INTO provider_event(event_id, notification_id) VALUES (?,?) ON"
                                + " CONFLICT DO NOTHING",
                        eventId,
                        notificationId)
                == 1;
    }

    public boolean isOptedOut(String tenant, String recipient) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM opt_out WHERE tenant_id = ? AND recipient = ?",
                        Integer.class,
                        tenant == null ? "" : tenant,
                        recipient);
        return n != null && n > 0;
    }

    public void addOptOut(String tenant, String recipient) {
        jdbc.update(
                "INSERT INTO opt_out(tenant_id, recipient) VALUES (?,?) ON CONFLICT DO NOTHING",
                tenant,
                recipient);
    }

    public void removeOptOut(String tenant, String recipient) {
        jdbc.update("DELETE FROM opt_out WHERE tenant_id = ? AND recipient = ?", tenant, recipient);
    }
}
