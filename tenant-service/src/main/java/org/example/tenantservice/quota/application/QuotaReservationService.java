package org.example.tenantservice.quota.application;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.example.tenantservice.quota.application.port.QuotaLedgerPort;
import org.example.tenantservice.quota.application.port.QuotaLedgerPort.Reservation;
import org.example.tenantservice.quota.application.port.QuotaLedgerPort.ReserveReply;
import org.example.tenantservice.quota.application.port.QuotaStorePort;
import org.example.tenantservice.quota.application.port.QuotaStorePort.CommitItem;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.QuotaSnapshot;
import org.example.tenantservice.quota.domain.ReserveResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Exact quota use cases: reserve (atomic hold), commit (durable count on SENT), release (drop hold
 * on FAILED). See ADR 0004. Ledger = hot path (Redis), store = durable truth (Postgres).
 */
@Service
@Slf4j
public class QuotaReservationService {

    public record ReserveOutcome(ReserveResult result, QuotaSnapshot snapshot) {}

    private final QuotaLedgerPort ledger;
    private final QuotaStorePort store;
    private final Duration defaultTtl;

    public QuotaReservationService(
            QuotaLedgerPort ledger,
            QuotaStorePort store,
            @Value("${app.quota.reservation-ttl:PT15M}") Duration defaultTtl) {
        this.ledger = ledger;
        this.store = store;
        this.defaultTtl = defaultTtl;
    }

    /**
     * Atomically hold one unit. Lazily initializes (or rebuilds after Redis loss) the ledger from
     * the durable store. Throws if the ledger is unavailable: callers must fail closed.
     */
    public ReserveOutcome reserve(String tenantId, String reservationId, Duration ttl) {
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? defaultTtl : ttl;
        ReserveReply reply = ledger.reserve(tenantId, reservationId, effective);
        if (reply.result() == Reservation.NOT_INITIALIZED) {
            QuotaSnapshot durable = store.find(tenantId).orElse(null);
            if (durable == null) {
                return new ReserveOutcome(ReserveResult.UNKNOWN_TENANT, null);
            }
            ledger.initialize(tenantId, durable.limit(), durable.used());
            reply = ledger.reserve(tenantId, reservationId, effective);
            if (reply.result() == Reservation.NOT_INITIALIZED) {
                throw new IllegalStateException("Quota ledger not initialized for " + tenantId);
            }
        }
        ReserveResult result =
                reply.result() == Reservation.GRANTED
                        ? ReserveResult.GRANTED
                        : ReserveResult.EXCEEDED;
        return new ReserveOutcome(result, reply.snapshot());
    }

    /** Drop a hold (notification FAILED or request aborted). Idempotent. */
    public boolean release(String tenantId, String reservationId) {
        return ledger.release(tenantId, reservationId);
    }

    /** Count delivered notifications durably, then settle the ledger. Idempotent per messageId. */
    public Map<String, CommitOutcome> commit(Collection<CommitItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        // 1. durable first (one DB transaction). If this throws nothing else happened.
        Map<String, CommitOutcome> outcomes = store.commitBatch(items);

        // 2. settle the ledger: idempotent (drop holds, used = max(used, durable)). Runs also for
        //    duplicates so a crash between step 1 and 2 heals on redelivery.
        Map<String, List<CommitItem>> byTenant =
                items.stream().collect(Collectors.groupingBy(CommitItem::tenantId));
        byTenant.forEach(
                (tenantId, tenantItems) -> {
                    QuotaSnapshot durable = store.find(tenantId).orElse(null);
                    if (durable == null) {
                        return;
                    }
                    ledger.commit(
                            tenantId,
                            tenantItems.stream().map(CommitItem::messageId).toList(),
                            durable.used());
                });
        outcomes.forEach(
                (id, o) -> {
                    if (o == CommitOutcome.LIMIT_REACHED || o == CommitOutcome.UNKNOWN_TENANT) {
                        log.warn("Quota commit for {} ended as {}", id, o);
                    }
                });
        return outcomes;
    }

    public CommitOutcome commit(String tenantId, String messageId) {
        return commit(List.of(new CommitItem(messageId, tenantId))).get(messageId);
    }

    /** Non-binding view for the legacy CheckQuota RPC (ledger first, durable fallback). */
    public QuotaSnapshot check(String tenantId) {
        try {
            return ledger.snapshot(tenantId).orElseGet(() -> store.find(tenantId).orElse(null));
        } catch (RuntimeException e) {
            log.warn("Ledger unavailable for check, using durable store: {}", e.getMessage());
            return store.find(tenantId).orElse(null);
        }
    }
}
