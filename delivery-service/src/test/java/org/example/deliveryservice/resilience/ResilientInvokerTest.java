package org.example.deliveryservice.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.provider.DeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@DisplayName("ResilientInvoker")
class ResilientInvokerTest {

    private ResilientInvoker invoker;

    @BeforeEach
    void setUp() {
        DeliveryProperties p = new DeliveryProperties();
        var r = p.getResilience();
        r.setSlidingWindowSize(4);
        r.setMinimumCalls(4);
        r.setFailureRateThreshold(50);
        r.setOpenStateWait(Duration.ofMillis(100));
        r.setHalfOpenCalls(2);
        r.setMaxConcurrentPerChannelLane(2);
        r.setBulkheadMaxWait(Duration.ZERO);
        invoker = new ResilientInvoker(p);
    }

    private void fail(String channel) {
        assertThrows(
                DeliveryException.class,
                () ->
                        invoker.invoke(
                                channel,
                                "normal",
                                () -> {
                                    throw DeliveryException.retryable("5xx", (Throwable) null);
                                }));
    }

    @Test
    @DisplayName("breaker opens on failures, fails fast, then closes after successful probes")
    void opensAndCloses() throws Exception {
        for (int i = 0; i < 4; i++) {
            fail("sms");
        }
        assertEquals(CircuitBreaker.State.OPEN, invoker.breaker("sms").getState());

        DeliveryException open =
                assertThrows(
                        DeliveryException.class,
                        () -> invoker.invoke("sms", "normal", () -> "never called"));
        assertEquals(Duration.ofMillis(100), open.retryAfter());

        Thread.sleep(150);
        assertEquals("ok", invoker.invoke("sms", "normal", () -> "ok"));
        assertEquals("ok", invoker.invoke("sms", "normal", () -> "ok"));
        assertEquals(CircuitBreaker.State.CLOSED, invoker.breaker("sms").getState());
    }

    @Test
    @DisplayName("429 and permanent errors do not trip the breaker")
    void rateLimitIgnored() {
        for (int i = 0; i < 10; i++) {
            assertThrows(
                    DeliveryException.class,
                    () ->
                            invoker.invoke(
                                    "push",
                                    "normal",
                                    () -> {
                                        throw DeliveryException.rateLimited(
                                                "429", Duration.ofSeconds(1));
                                    }));
        }
        assertEquals(CircuitBreaker.State.CLOSED, invoker.breaker("push").getState());
    }

    @Test
    @DisplayName("hung provider only occupies its bulkhead; extra calls are rejected immediately")
    void bulkheadRejectsWhenProviderHangs() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(
                    () ->
                            invoker.invoke(
                                    "chat",
                                    "normal",
                                    () -> {
                                        inside.countDown();
                                        try {
                                            release.await(5, TimeUnit.SECONDS);
                                        } catch (InterruptedException ignored) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return "late";
                                    }));
        }
        inside.await(2, TimeUnit.SECONDS);

        long t0 = System.nanoTime();
        DeliveryException ex =
                assertThrows(
                        DeliveryException.class, () -> invoker.invoke("chat", "normal", () -> "x"));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(true, ms < 500, "rejection must be fast, was " + ms + "ms");
        assertEquals(true, ex.getMessage().contains("bulkhead"));
        // another lane of the same channel is isolated
        assertEquals("ok", invoker.invoke("chat", "high", () -> "ok"));
        release.countDown();
        pool.shutdown();
    }
}
