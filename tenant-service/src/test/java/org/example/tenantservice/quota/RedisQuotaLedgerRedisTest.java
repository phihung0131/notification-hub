package org.example.tenantservice.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.tenantservice.quota.adapters.redis.RedisQuotaLedger;
import org.example.tenantservice.quota.application.port.QuotaLedgerPort.Reservation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies the Lua scripts against a REAL Redis. Skipped unless a Redis is reachable at {@code
 * -Dtest.redis.host/-Dtest.redis.port} (default localhost:6379). Run e.g. {@code docker run --rm -p
 * 6390:6379 redis:7} then {@code mvn test -Dtest.redis.port=6390}.
 */
class RedisQuotaLedgerRedisTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        String host = System.getProperty("test.redis.host", "localhost");
        int port = Integer.getInteger("test.redis.port", 6379);
        try {
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
            factory.afterPropertiesSet();
            template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            factory = null;
        }
        assumeTrue(factory != null, "no Redis reachable, skipping Lua tests");
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static String tenant() {
        return "t-" + UUID.randomUUID();
    }

    @Test
    void parallelReserveNeverExceedsLimit() throws Exception {
        RedisQuotaLedger ledger = new RedisQuotaLedger(template);
        String t = tenant();
        ledger.initialize(t, 25, 0);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            fs.add(
                    pool.submit(
                            () -> {
                                if (ledger.reserve(
                                                        t,
                                                        UUID.randomUUID().toString(),
                                                        Duration.ofMinutes(1))
                                                .result()
                                        == Reservation.GRANTED) {
                                    granted.incrementAndGet();
                                }
                            }));
        }
        for (Future<?> f : fs) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(25, granted.get());
        assertEquals(25, ledger.snapshot(t).orElseThrow().held());
    }

    @Test
    void notInitializedThenInitializeIsIdempotentAndNotClobbering() {
        RedisQuotaLedger ledger = new RedisQuotaLedger(template);
        String t = tenant();
        assertEquals(
                Reservation.NOT_INITIALIZED,
                ledger.reserve(t, "a", Duration.ofMinutes(1)).result());
        ledger.initialize(t, 2, 1);
        ledger.initialize(t, 100, 99); // must not clobber
        assertEquals(2, ledger.snapshot(t).orElseThrow().limit());
        assertEquals(Reservation.GRANTED, ledger.reserve(t, "a", Duration.ofMinutes(1)).result());
        assertEquals(Reservation.GRANTED, ledger.reserve(t, "a", Duration.ofMinutes(1)).result());
        assertEquals(Reservation.EXCEEDED, ledger.reserve(t, "b", Duration.ofMinutes(1)).result());
    }

    @Test
    void holdsExpireByTtl() throws Exception {
        RedisQuotaLedger ledger = new RedisQuotaLedger(template);
        String t = tenant();
        ledger.initialize(t, 1, 0);
        assertEquals(Reservation.GRANTED, ledger.reserve(t, "a", Duration.ofMillis(200)).result());
        assertEquals(Reservation.EXCEEDED, ledger.reserve(t, "b", Duration.ofMinutes(1)).result());
        Thread.sleep(400);
        assertEquals(Reservation.GRANTED, ledger.reserve(t, "b", Duration.ofMinutes(1)).result());
    }

    @Test
    void commitReleaseAndMonotonicSync() {
        RedisQuotaLedger ledger = new RedisQuotaLedger(template);
        String t = tenant();
        ledger.initialize(t, 5, 0);
        ledger.reserve(t, "a", Duration.ofMinutes(1));
        ledger.reserve(t, "b", Duration.ofMinutes(1));
        ledger.commit(t, List.of("a"), 1);
        assertTrue(ledger.release(t, "b"));
        assertEquals(false, ledger.release(t, "b"));
        var s = ledger.snapshot(t).orElseThrow();
        assertEquals(1, s.used());
        assertEquals(0, s.held());
        ledger.sync(t, 5, 0); // stale sync must not move used backwards
        assertEquals(1, ledger.snapshot(t).orElseThrow().used());
        ledger.sync(t, 7, 3);
        assertEquals(3, ledger.snapshot(t).orElseThrow().used());
        assertEquals(7, ledger.snapshot(t).orElseThrow().limit());
    }

    @Test
    void unlimitedNeverExceeds() {
        RedisQuotaLedger ledger = new RedisQuotaLedger(template);
        String t = tenant();
        ledger.initialize(t, -1, 0);
        for (int i = 0; i < 50; i++) {
            assertEquals(
                    Reservation.GRANTED,
                    ledger.reserve(t, "r" + i, Duration.ofMinutes(1)).result());
        }
    }
}
