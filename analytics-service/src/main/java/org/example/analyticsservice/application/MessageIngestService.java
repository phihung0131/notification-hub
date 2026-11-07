package org.example.analyticsservice.application;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.analyticsservice.application.port.MessageWritePort;
import org.example.analyticsservice.domain.MessageRecord;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ingests batches of requested/result events. The caller acknowledges Kafka offsets only after
 * {@link #ingest} returns, so a crash re-delivers events and the idempotent upsert absorbs them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageIngestService {

    private final MessageWritePort writePort;

    /** Persists the batch; throws on DB failure so the caller does not ack. */
    public void ingest(Collection<NotificationEvent> events) {
        Map<UUID, MessageRecord> folded = new LinkedHashMap<>();
        int dropped = 0;
        for (NotificationEvent e : events) {
            MessageRecord r = toRecord(e);
            if (r == null) {
                dropped++;
                continue;
            }
            folded.merge(r.messageId(), r, MessageRecord::mergeWith);
        }
        if (dropped > 0) {
            log.warn("Dropped {} events without id/tenantId", dropped);
        }
        if (folded.isEmpty()) {
            return;
        }
        writePort.upsertAll(List.copyOf(folded.values()));
        log.debug("Ingested {} events as {} messages", events.size(), folded.size());
    }

    static MessageRecord toRecord(NotificationEvent e) {
        if (e == null || e.getId() == null || e.getTenantId() == null) {
            return null;
        }
        Instant now = Instant.now();
        Instant created = e.getCreatedAt() != null ? e.getCreatedAt() : now;
        NotificationStatus status =
                e.getStatus() != null ? e.getStatus() : NotificationStatus.PENDING;
        return new MessageRecord(
                UUID.fromString(e.getId().toString()),
                e.getTenantId().toString(),
                str(e.getChannel()),
                str(e.getRecipient()),
                str(e.getSubject()),
                str(e.getContent()),
                status,
                created,
                now);
    }

    private static String str(CharSequence s) {
        return s == null ? null : s.toString();
    }
}
