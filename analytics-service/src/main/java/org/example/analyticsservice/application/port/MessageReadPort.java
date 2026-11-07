package org.example.analyticsservice.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.example.analyticsservice.domain.MessagePage;
import org.example.analyticsservice.domain.MessageRecord;
import org.example.analyticsservice.domain.MessageSearchCriteria;
import org.example.events.enums.NotificationStatus;

public interface MessageReadPort {

    Optional<MessageRecord> findById(UUID id);

    /** Keyset page ordered by (created_at DESC, message_id DESC); cursor may be null. */
    MessagePage search(MessageSearchCriteria criteria, String cursor, int limit);

    Map<NotificationStatus, Long> countByStatus(String tenantId, Instant from, Instant to);
}
