package org.example.notificationservice.adapters.jdbc;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.example.notificationservice.application.port.NotificationStatusPort;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.domain.NotificationStateMachine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Guarded UPDATE: the WHERE clause encodes the state machine so it never regresses. */
@Component
public class JdbcNotificationStatusAdapter implements NotificationStatusPort {

    private final JdbcTemplate jdbc;

    public JdbcNotificationStatusAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Result transition(UUID id, NotificationStatus to) {
        Set<NotificationStatus> sources = NotificationStateMachine.allowedSources(to);
        if (!sources.isEmpty()) {
            String in =
                    sources.stream()
                            .map(s -> "'" + s.name() + "'")
                            .collect(Collectors.joining(","));
            int n =
                    jdbc.update(
                            "UPDATE notifications SET status = ?, updated_at = now()"
                                    + " WHERE id = ? AND status IN ("
                                    + in
                                    + ")",
                            to.name(),
                            id);
            if (n == 1) {
                return Result.UPDATED;
            }
        }
        Integer exists =
                jdbc.queryForObject(
                        "SELECT count(*) FROM notifications WHERE id = ?", Integer.class, id);
        return exists != null && exists > 0 ? Result.IGNORED : Result.NOT_FOUND;
    }
}
