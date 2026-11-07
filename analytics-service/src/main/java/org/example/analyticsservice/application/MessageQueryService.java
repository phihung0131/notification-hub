package org.example.analyticsservice.application;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.example.analyticsservice.application.port.MessageReadPort;
import org.example.analyticsservice.domain.MessagePage;
import org.example.analyticsservice.domain.MessageRecord;
import org.example.analyticsservice.domain.MessageSearchCriteria;
import org.example.events.enums.NotificationStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Tenant-scoped read use cases. */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final MessageReadPort readPort;

    /** Returns the message only if it belongs to the tenant (isolation). */
    public Optional<MessageRecord> getById(String tenantId, UUID id) {
        return readPort.findById(id).filter(m -> tenantId.equals(m.tenantId()));
    }

    public MessagePage search(MessageSearchCriteria criteria, String cursor, Integer limit) {
        int effective = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return readPort.search(criteria, cursor, effective);
    }

    /** Counts per status; every status is present (zero when absent). */
    public Map<NotificationStatus, Long> stats(String tenantId, Instant from, Instant to) {
        Map<NotificationStatus, Long> result = new EnumMap<>(NotificationStatus.class);
        for (NotificationStatus s : NotificationStatus.values()) {
            result.put(s, 0L);
        }
        result.putAll(readPort.countByStatus(tenantId, from, to));
        return result;
    }
}
