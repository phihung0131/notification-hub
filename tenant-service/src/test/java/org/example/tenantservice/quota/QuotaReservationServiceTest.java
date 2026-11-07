package org.example.tenantservice.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.tenantservice.quota.application.QuotaReservationService;
import org.example.tenantservice.quota.application.QuotaReservationService.ReserveOutcome;
import org.example.tenantservice.quota.application.port.QuotaStorePort.CommitItem;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.ReserveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotaReservationServiceTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private InMemoryQuota.Ledger ledger;
    private InMemoryQuota.Store store;
    private QuotaReservationService service;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryQuota.Ledger();
        store = new InMemoryQuota.Store();
        service = new QuotaReservationService(ledger, store, TTL);
    }

    @Test
    void parallelReserveNeverExceedsLimit() throws Exception {
        store.tenant("t1", 50, 0);
        int threads = 64;
        int attempts = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            fs.add(
                    pool.submit(
                            () -> {
                                start.await();
                                ReserveOutcome o =
                                        service.reserve("t1", UUID.randomUUID().toString(), null);
                                if (o.result() == ReserveResult.GRANTED) {
                                    granted.incrementAndGet();
                                }
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(50, granted.get(), "exactly limit reservations granted, never more");
        assertEquals(50, ledger.snapshot("t1").orElseThrow().held());
    }

    @Test
    void reserveCommitReleaseCycleStaysWithinLimitAcrossConcurrentFlow() throws Exception {
        store.tenant("t1", 20, 0);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger committed = new AtomicInteger();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            final int n = i;
            fs.add(
                    pool.submit(
                            () -> {
                                String id = UUID.randomUUID().toString();
                                if (service.reserve("t1", id, null).result()
                                        == ReserveResult.GRANTED) {
                                    if (n % 3 == 0) {
                                        service.release("t1", id); // FAILED
                                    } else {
                                        service.commit("t1", id); // SENT
                                        committed.incrementAndGet();
                                    }
                                }
                                return null;
                            }));
        }
        for (Future<?> f : fs) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(store.used("t1") <= 20, "durable used never exceeds the limit");
        assertEquals(committed.get(), store.used("t1"));
    }

    @Test
    void reserveIsIdempotentPerReservationId() {
        store.tenant("t1", 2, 0);
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "a", null).result());
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "a", null).result());
        assertEquals(1, ledger.snapshot("t1").orElseThrow().held());
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "b", null).result());
        assertEquals(ReserveResult.EXCEEDED, service.reserve("t1", "c", null).result());
    }

    @Test
    void releaseFreesTheUnit() {
        store.tenant("t1", 1, 0);
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "a", null).result());
        assertEquals(ReserveResult.EXCEEDED, service.reserve("t1", "b", null).result());
        assertTrue(service.release("t1", "a"));
        assertFalse(service.release("t1", "a"), "second release is a no-op");
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "b", null).result());
    }

    @Test
    void expiredReservationAutoReleasesAfterCrash() {
        store.tenant("t1", 1, 0);
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "crashed", null).result());
        assertEquals(ReserveResult.EXCEEDED, service.reserve("t1", "b", null).result());
        ledger.now = ledger.now.plus(TTL).plusSeconds(1); // reserver died, TTL elapses
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "b", null).result());
    }

    @Test
    void commitCountsDurablyAndOnlyOncePerMessage() {
        store.tenant("t1", 10, 0);
        service.reserve("t1", "m1", null);
        assertEquals(CommitOutcome.COMMITTED, service.commit("t1", "m1"));
        assertEquals(CommitOutcome.DUPLICATE, service.commit("t1", "m1"));
        assertEquals(1, store.used("t1"));
        assertEquals(0, ledger.snapshot("t1").orElseThrow().held());
        assertEquals(1, ledger.snapshot("t1").orElseThrow().used());
    }

    @Test
    void commitIsCappedByDurableLimitEvenWithoutAReservation() {
        store.tenant("t1", 1, 0);
        List<CommitItem> items = List.of(new CommitItem("a", "t1"), new CommitItem("b", "t1"));
        var out = service.commit(items);
        assertEquals(CommitOutcome.COMMITTED, out.get("a"));
        assertEquals(CommitOutcome.LIMIT_REACHED, out.get("b"));
        assertEquals(1, store.used("t1"));
    }

    @Test
    void redisLossIsRebuiltFromDurableStore() {
        store.tenant("t1", 3, 0);
        service.reserve("t1", "a", null);
        service.commit("t1", "a");
        service.reserve("t1", "b", null);
        service.commit("t1", "b");
        ledger.wipe(); // total Redis data loss
        assertEquals(ReserveResult.GRANTED, service.reserve("t1", "c", null).result());
        assertEquals(2, ledger.snapshot("t1").orElseThrow().used(), "used rebuilt from Postgres");
        service.commit("t1", "c");
        assertEquals(ReserveResult.EXCEEDED, service.reserve("t1", "d", null).result());
    }

    @Test
    void unlimitedTenantIsNeverRejected() {
        store.tenant("t1", -1, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(ReserveResult.GRANTED, service.reserve("t1", "r" + i, null).result());
        }
    }

    @Test
    void unknownTenant() {
        assertEquals(ReserveResult.UNKNOWN_TENANT, service.reserve("nope", "a", null).result());
    }

    @Test
    void ledgerOutagePropagatesSoCallersFailClosed() {
        store.tenant("t1", 5, 0);
        ledger.down.set(true);
        assertThrows(RuntimeException.class, () -> service.reserve("t1", "a", null));
    }
}
