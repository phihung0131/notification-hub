package org.example.tenantservice.quota.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.tenantservice.quota.application.port.QuotaStorePort.CommitItem;
import org.example.tenantservice.quota.domain.ResultEvent;
import org.example.tenantservice.quota.domain.ResultEvent.Outcome;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a batch of delivery results to quota: SENT commits (durable, idempotent), FAILED releases
 * the hold. Returns only after the durable write is done, so the caller may then commit its Kafka
 * offsets. There is no in-memory buffer: a crash before return means redelivery, which is safe
 * because every step is idempotent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultEventProcessor {

    private final QuotaReservationService quota;

    public void process(List<ResultEvent> events) {
        // dedup within the batch; SENT wins over FAILED for the same id
        Map<String, ResultEvent> unique = new LinkedHashMap<>();
        for (ResultEvent e : events) {
            if (e == null || e.messageId() == null || e.tenantId() == null) {
                log.warn("Skipping malformed result event: {}", e);
                continue;
            }
            unique.merge(e.messageId(), e, (a, b) -> a.outcome() == Outcome.SENT ? a : b);
        }
        List<CommitItem> sent =
                unique.values().stream()
                        .filter(e -> e.outcome() == Outcome.SENT)
                        .map(e -> new CommitItem(e.messageId(), e.tenantId()))
                        .toList();
        quota.commit(sent);

        unique.values().stream()
                .filter(e -> e.outcome() == Outcome.FAILED)
                .forEach(e -> quota.release(e.tenantId(), e.messageId()));
        log.debug("Processed {} result events ({} unique)", events.size(), unique.size());
    }
}
