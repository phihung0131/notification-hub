package org.example.tenantservice.quota;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.example.tenantservice.quota.application.port.QuotaLedgerPort;
import org.example.tenantservice.quota.application.port.QuotaStorePort;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.QuotaSnapshot;

/**
 * In-memory fakes of the two quota ports with the same semantics as the Redis (Lua) and Postgres
 * adapters. The Lua semantics themselves are verified separately against a real Redis
 * (RedisQuotaLedgerRedisTest).
 */
public final class InMemoryQuota {

    private InMemoryQuota() {}

    /** Mirrors the Lua scripts: every method is atomic (single lock). */
    public static class Ledger implements QuotaLedgerPort {
        private final Map<String, int[]> state = new HashMap<>(); // limit, used
        private final Map<String, Map<String, Instant>> holds = new HashMap<>();
        public Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public final AtomicBoolean down = new AtomicBoolean(false);

        private void check() {
            if (down.get()) {
                throw new IllegalStateException("redis down");
            }
        }

        private int purge(String t) {
            Map<String, Instant> h = holds.computeIfAbsent(t, k -> new HashMap<>());
            h.values().removeIf(exp -> !exp.isAfter(now));
            return h.size();
        }

        /** Simulates total Redis data loss. */
        public synchronized void wipe() {
            state.clear();
            holds.clear();
        }

        @Override
        public synchronized ReserveReply reserve(String t, String id, Duration ttl) {
            check();
            int[] s = state.get(t);
            if (s == null) {
                return new ReserveReply(Reservation.NOT_INITIALIZED, null);
            }
            int held = purge(t);
            Map<String, Instant> h = holds.get(t);
            if (h.containsKey(id)) {
                return new ReserveReply(Reservation.GRANTED, new QuotaSnapshot(s[0], s[1], held));
            }
            if (s[0] >= 0 && s[1] + held >= s[0]) {
                return new ReserveReply(Reservation.EXCEEDED, new QuotaSnapshot(s[0], s[1], held));
            }
            h.put(id, now.plus(ttl));
            return new ReserveReply(Reservation.GRANTED, new QuotaSnapshot(s[0], s[1], held + 1));
        }

        @Override
        public synchronized void initialize(String t, int limit, int used) {
            check();
            state.putIfAbsent(t, new int[] {limit, used});
        }

        @Override
        public synchronized void sync(String t, int limit, int used) {
            check();
            int[] s = state.computeIfAbsent(t, k -> new int[] {limit, used});
            s[0] = limit;
            s[1] = Math.max(s[1], used);
        }

        @Override
        public synchronized void commit(String t, Collection<String> ids, int durableUsed) {
            check();
            Map<String, Instant> h = holds.computeIfAbsent(t, k -> new HashMap<>());
            ids.forEach(h::remove);
            int[] s = state.computeIfAbsent(t, k -> new int[] {-1, 0});
            s[1] = Math.max(s[1], durableUsed);
        }

        @Override
        public synchronized boolean release(String t, String id) {
            check();
            return holds.computeIfAbsent(t, k -> new HashMap<>()).remove(id) != null;
        }

        @Override
        public synchronized Optional<QuotaSnapshot> snapshot(String t) {
            check();
            int[] s = state.get(t);
            return s == null
                    ? Optional.empty()
                    : Optional.of(new QuotaSnapshot(s[0], s[1], purge(t)));
        }
    }

    /** Mirrors JdbcQuotaStore: one atomic transaction per commitBatch, unique message ids. */
    public static class Store implements QuotaStorePort {
        private final Map<String, int[]> tenants = new TreeMap<>(); // limit, used
        private final Set<String> processed = new HashSet<>();
        public final AtomicBoolean failNextCommit = new AtomicBoolean(false);

        public synchronized void tenant(String id, int limit, int used) {
            tenants.put(id, new int[] {limit, used});
        }

        public synchronized int used(String id) {
            return tenants.get(id)[1];
        }

        @Override
        public synchronized Optional<QuotaSnapshot> find(String id) {
            int[] s = tenants.get(id);
            return s == null ? Optional.empty() : Optional.of(new QuotaSnapshot(s[0], s[1], 0));
        }

        @Override
        public synchronized List<String> allTenantIds() {
            return new ArrayList<>(tenants.keySet());
        }

        @Override
        public synchronized Map<String, CommitOutcome> commitBatch(Collection<CommitItem> items) {
            if (failNextCommit.getAndSet(false)) {
                throw new IllegalStateException("db down");
            }
            Map<String, CommitOutcome> out = new LinkedHashMap<>();
            Map<String, List<CommitItem>> by = new TreeMap<>();
            items.forEach(i -> by.computeIfAbsent(i.tenantId(), k -> new ArrayList<>()).add(i));
            by.forEach(
                    (t, list) -> {
                        int[] s = tenants.get(t);
                        if (s == null) {
                            list.forEach(i -> out.put(i.messageId(), CommitOutcome.UNKNOWN_TENANT));
                            return;
                        }
                        List<CommitItem> fresh = new ArrayList<>();
                        for (CommitItem i : list) {
                            if (processed.add(i.messageId())) {
                                fresh.add(i);
                            } else {
                                out.put(i.messageId(), CommitOutcome.DUPLICATE);
                            }
                        }
                        int allowed =
                                s[0] < 0
                                        ? fresh.size()
                                        : Math.max(0, Math.min(fresh.size(), s[0] - s[1]));
                        s[1] += allowed;
                        for (int k = 0; k < fresh.size(); k++) {
                            out.put(
                                    fresh.get(k).messageId(),
                                    k < allowed
                                            ? CommitOutcome.COMMITTED
                                            : CommitOutcome.LIMIT_REACHED);
                        }
                    });
            return out;
        }

        @Override
        public int deleteProcessedBefore(Instant cutoff) {
            return 0;
        }
    }
}
