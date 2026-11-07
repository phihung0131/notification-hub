package org.example.tenantservice.quota.application;

import java.time.Duration;
import java.time.Instant;

import org.example.tenantservice.quota.application.port.QuotaLedgerPort;
import org.example.tenantservice.quota.application.port.QuotaStorePort;
import org.example.tenantservice.quota.domain.QuotaSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rebuilds / aligns the Redis ledger from the durable store: at startup and periodically. Safe to
 * run concurrently with commits because used only moves forward (see {@link QuotaLedgerPort#sync}).
 * Holds lost with Redis are not recoverable: see ADR 0004.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaReconciliationService {

    private final QuotaLedgerPort ledger;
    private final QuotaStorePort store;

    @Value("${app.quota.processed-retention:P30D}")
    private Duration processedRetention = Duration.ofDays(30);

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcileAll();
    }

    @Scheduled(
            fixedDelayString = "${app.quota.reconcile-interval-ms:60000}",
            initialDelayString = "${app.quota.reconcile-interval-ms:60000}")
    public void scheduled() {
        reconcileAll();
    }

    /**
     * Idempotency records only need to outlive the Kafka retention of the result topic. Default 30
     * days (topic retention is 7 days), so a late redelivery cannot be double counted.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupProcessedEvents() {
        try {
            Instant cutoff = Instant.now().minus(processedRetention);
            int n = store.deleteProcessedBefore(cutoff);
            log.info("Deleted {} processed_events older than {}", n, cutoff);
        } catch (RuntimeException e) {
            log.warn("processed_events cleanup failed: {}", e.getMessage());
        }
    }

    /**
     * @return number of tenants synced
     */
    public int reconcileAll() {
        int count = 0;
        try {
            for (String tenantId : store.allTenantIds()) {
                QuotaSnapshot durable = store.find(tenantId).orElse(null);
                if (durable != null) {
                    ledger.sync(tenantId, durable.limit(), durable.used());
                    count++;
                }
            }
            log.debug("Quota ledger reconciled for {} tenants", count);
        } catch (RuntimeException e) {
            log.warn("Quota reconciliation failed, will retry: {}", e.getMessage());
        }
        return count;
    }
}
