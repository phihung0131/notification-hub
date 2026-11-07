package org.example.tenantservice.quota.application.port;

import java.time.Duration;
import java.util.Collection;

import org.example.tenantservice.quota.domain.QuotaSnapshot;

/**
 * Hot-path quota ledger (Redis). Reserve is atomic: it checks {@code used + held < limit} and adds
 * the hold in one step, so concurrent callers can never be granted more than the limit.
 */
public interface QuotaLedgerPort {

    enum Reservation {
        GRANTED,
        EXCEEDED,
        /** Ledger has no state for this tenant (first use or Redis data loss). */
        NOT_INITIALIZED
    }

    record ReserveReply(Reservation result, QuotaSnapshot snapshot) {}

    /** Idempotent per reservationId: a repeated reserve of a live hold is GRANTED again. */
    ReserveReply reserve(String tenantId, String reservationId, Duration ttl);

    /** Create ledger state only if absent (never clobbers live state). */
    void initialize(String tenantId, int limit, int used);

    /**
     * Align ledger with durable truth: limit is overwritten, used moves forward only (max). Used
     * never decreases, so a late sync cannot cause a double count.
     */
    void sync(String tenantId, int limit, int used);

    /** Drop the holds and move used forward to the durable value. Idempotent. */
    void commit(String tenantId, Collection<String> reservationIds, int durableUsed);

    /** Drop a hold. Returns true if a live hold existed. Idempotent. */
    boolean release(String tenantId, String reservationId);

    /** Current ledger state, empty when not initialized. */
    java.util.Optional<QuotaSnapshot> snapshot(String tenantId);
}
