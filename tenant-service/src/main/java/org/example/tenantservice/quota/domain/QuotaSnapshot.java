package org.example.tenantservice.quota.domain;

/**
 * Point-in-time view of a tenant quota. {@code limit == -1} means unlimited.
 *
 * @param limit max committed units, -1 for unlimited
 * @param used committed units (durable truth)
 * @param held units currently reserved but not yet committed or released
 */
public record QuotaSnapshot(int limit, int used, int held) {

    public static final int UNLIMITED = -1;

    public boolean unlimited() {
        return limit == UNLIMITED;
    }

    /** Units still available for new reservations, -1 when unlimited. */
    public int remaining() {
        return unlimited() ? UNLIMITED : Math.max(0, limit - used - held);
    }
}
