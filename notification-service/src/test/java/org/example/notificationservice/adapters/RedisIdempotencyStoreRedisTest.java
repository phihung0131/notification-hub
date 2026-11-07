package org.example.notificationservice.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.example.notificationservice.adapters.redis.RedisIdempotencyStore;
import org.example.notificationservice.application.port.IdempotencyPort.Begin;
import org.example.notificationservice.application.port.IdempotencyPort.Kind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Verifies SET NX semantics against a REAL Redis. Skipped unless Redis is reachable at
 * -Dtest.redis.host/-Dtest.redis.port (default localhost:6379).
 */
class RedisIdempotencyStoreRedisTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        try {
            factory =
                    new LettuceConnectionFactory(
                            new RedisStandaloneConfiguration(
                                    System.getProperty("test.redis.host", "localhost"),
                                    Integer.getInteger("test.redis.port", 6379)));
            factory.afterPropertiesSet();
            template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            factory = null;
        }
        assumeTrue(factory != null, "no Redis reachable, skipping");
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void exactlyOneOfNConcurrentCallersProceeds() throws Exception {
        RedisIdempotencyStore store = new RedisIdempotencyStore(template);
        String tenant = "t-" + UUID.randomUUID();
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Begin>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String owner = "o" + i;
            fs.add(
                    pool.submit(
                            () -> {
                                go.await();
                                return store.begin(
                                        tenant, "k", "fp", owner, Duration.ofSeconds(30));
                            }));
        }
        go.countDown();
        int proceed = 0;
        int inFlight = 0;
        for (Future<Begin> f : fs) {
            Kind k = f.get(10, TimeUnit.SECONDS).kind();
            if (k == Kind.PROCEED) {
                proceed++;
            } else if (k == Kind.IN_FLIGHT) {
                inFlight++;
            }
        }
        pool.shutdown();
        assertEquals(1, proceed);
        assertEquals(n - 1, inFlight);
    }

    @Test
    void completeThenReplayMismatchAndAbort() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(template);
        String tenant = "t-" + UUID.randomUUID();
        assertEquals(
                Kind.PROCEED, store.begin(tenant, "k", "fp", "me", Duration.ofSeconds(30)).kind());
        assertEquals(
                Kind.MISMATCH,
                store.begin(tenant, "k", "other", "x", Duration.ofSeconds(30)).kind());

        // abort by a non-owner must not release someone else's claim
        store.abort(tenant, "k", "fp", "not-me");
        assertEquals(
                Kind.IN_FLIGHT, store.begin(tenant, "k", "fp", "x", Duration.ofSeconds(30)).kind());

        store.complete(tenant, "k", "fp", "notif-1", Duration.ofHours(24));
        Begin replay = store.begin(tenant, "k", "fp", "x", Duration.ofSeconds(30));
        assertEquals(Kind.REPLAY, replay.kind());
        assertEquals("notif-1", replay.notificationId());

        // owner abort frees an in-flight claim
        assertEquals(
                Kind.PROCEED, store.begin(tenant, "k2", "fp", "me", Duration.ofSeconds(30)).kind());
        store.abort(tenant, "k2", "fp", "me");
        assertEquals(
                Kind.PROCEED,
                store.begin(tenant, "k2", "fp", "me2", Duration.ofSeconds(30)).kind());
    }

    @Test
    void inFlightClaimExpires() throws Exception {
        RedisIdempotencyStore store = new RedisIdempotencyStore(template);
        String tenant = "t-" + UUID.randomUUID();
        store.begin(tenant, "k", "fp", "crashed", Duration.ofMillis(200));
        Thread.sleep(400);
        assertEquals(
                Kind.PROCEED,
                store.begin(tenant, "k", "fp", "retry", Duration.ofSeconds(30)).kind());
    }
}
