package org.example.tenantservice.quota.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.QuotaSnapshot;

/** Durable quota store (PostgreSQL): the source of truth for committed usage. */
public interface QuotaStorePort {

    /** One delivered notification to count. {@code messageId} is the idempotency key. */
    record CommitItem(String messageId, String tenantId) {}

    Optional<QuotaSnapshot> find(String tenantId);

    List<String> allTenantIds();

    /**
     * Atomically (single DB transaction): record each message id as processed (INSERT ... ON
     * CONFLICT DO NOTHING), and add the number of newly recorded ids per tenant to quota_used,
     * capped at the limit. Items must already be deduplicated by messageId.
     *
     * @return outcome per messageId
     */
    Map<String, CommitOutcome> commitBatch(Collection<CommitItem> items);

    /** Delete idempotency records older than the retention window. */
    int deleteProcessedBefore(java.time.Instant cutoff);
}
