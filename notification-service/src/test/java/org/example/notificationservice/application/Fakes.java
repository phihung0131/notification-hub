package org.example.notificationservice.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.notificationservice.application.port.IdempotencyPort;
import org.example.notificationservice.application.port.NotificationStatusPort;
import org.example.notificationservice.application.port.NotificationWritePort;
import org.example.notificationservice.application.port.QuotaPort;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.example.notificationservice.domain.DuplicateIdempotencyKeyException;
import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.domain.NotificationStateMachine;

/** In-memory ports with the same atomicity guarantees as the real adapters. */
final class Fakes {

    private Fakes() {}

    /** Same contract as RedisIdempotencyStore: begin is an atomic put-if-absent. */
    static class Idempotency implements IdempotencyPort {
        private record Entry(String fp, boolean done, String value) {}

        private final Map<String, Entry> map = new HashMap<>();
        final AtomicBoolean down = new AtomicBoolean(false);

        private static String k(String t, String key) {
            return t + "/" + key;
        }

        @Override
        public synchronized Begin begin(
                String t, String key, String fp, String owner, Duration ttl) {
            if (down.get()) {
                throw new IllegalStateException("redis down");
            }
            Entry e = map.get(k(t, key));
            if (e == null) {
                map.put(k(t, key), new Entry(fp, false, owner));
                return new Begin(Kind.PROCEED, null);
            }
            if (!e.fp().equals(fp)) {
                return new Begin(Kind.MISMATCH, null);
            }
            return e.done() ? new Begin(Kind.REPLAY, e.value()) : new Begin(Kind.IN_FLIGHT, null);
        }

        @Override
        public synchronized void complete(
                String t, String key, String fp, String nid, Duration ttl) {
            if (down.get()) {
                throw new IllegalStateException("redis down");
            }
            map.put(k(t, key), new Entry(fp, true, nid));
        }

        @Override
        public synchronized void abort(String t, String key, String fp, String owner) {
            Entry e = map.get(k(t, key));
            if (e != null && !e.done() && e.value().equals(owner)) {
                map.remove(k(t, key));
            }
        }

        synchronized int size() {
            return map.size();
        }
    }

    /** Exact reserve with a hard limit, like the Lua ledger. */
    static class Quota implements QuotaPort {
        private final int limit;
        private final Map<String, Integer> used = new HashMap<>();
        private final Map<String, String> holds = new HashMap<>();
        final AtomicBoolean down = new AtomicBoolean(false);
        final AtomicInteger released = new AtomicInteger();

        Quota(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized Result reserve(String tenantId, String reservationId) {
            if (down.get()) {
                throw new DependencyUnavailableException("quota-service", new RuntimeException());
            }
            if (holds.containsKey(reservationId)) {
                return Result.GRANTED;
            }
            if (holds.size() + used.getOrDefault(tenantId, 0) >= limit) {
                return Result.EXCEEDED;
            }
            holds.put(reservationId, tenantId);
            return Result.GRANTED;
        }

        @Override
        public synchronized void release(String tenantId, String reservationId) {
            if (holds.remove(reservationId) != null) {
                released.incrementAndGet();
            }
        }

        synchronized int held() {
            return holds.size();
        }
    }

    /** Unique (tenant, key) like the Postgres partial unique index. */
    static class Db implements NotificationWritePort {
        final List<NewNotification> rows = new ArrayList<>();
        final AtomicBoolean failNext = new AtomicBoolean(false);

        @Override
        public synchronized void insertWithOutbox(NewNotification n) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("db down");
            }
            if (n.idempotencyKey() != null
                    && rows.stream()
                            .anyMatch(
                                    r ->
                                            r.tenantId().equals(n.tenantId())
                                                    && n.idempotencyKey()
                                                            .equals(r.idempotencyKey()))) {
                throw new DuplicateIdempotencyKeyException("dup", null);
            }
            rows.add(n);
        }

        @Override
        public synchronized Optional<Existing> findByIdempotencyKey(String t, String key) {
            return rows.stream()
                    .filter(r -> r.tenantId().equals(t) && key.equals(r.idempotencyKey()))
                    .findFirst()
                    .map(r -> new Existing(r.id().toString(), r.fingerprint()));
        }

        synchronized int count() {
            return rows.size();
        }
    }

    /** State-machine guarded status store. */
    static class Status implements NotificationStatusPort {
        final Map<UUID, NotificationStatus> map = new HashMap<>();

        @Override
        public synchronized Result transition(UUID id, NotificationStatus to) {
            NotificationStatus cur = map.get(id);
            if (cur == null) {
                return Result.NOT_FOUND;
            }
            if (NotificationStateMachine.canTransition(cur, to)) {
                map.put(id, to);
                return Result.UPDATED;
            }
            return Result.IGNORED;
        }
    }
}
