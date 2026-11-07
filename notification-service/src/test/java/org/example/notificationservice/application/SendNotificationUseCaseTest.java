package org.example.notificationservice.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.application.port.TenantIdempotencyConfigPort;
import org.example.notificationservice.application.port.TenantIdempotencyConfigPort.Config;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.example.notificationservice.domain.IdempotencyMode;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.service.NotificationValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SendNotificationUseCaseTest {

    private Fakes.Idempotency idem;
    private Fakes.Quota quota;
    private Fakes.Db db;
    private IdempotencyMode mode = IdempotencyMode.HEADER_ONLY;
    private SendNotificationUseCase useCase;

    @BeforeEach
    void setUp() {
        idem = new Fakes.Idempotency();
        quota = new Fakes.Quota(1_000);
        db = new Fakes.Db();
        useCase = newUseCase(new OutboxWritePath(db));
    }

    private SendNotificationUseCase newUseCase(WritePath path) {
        TenantIdempotencyConfigPort cfg = t -> new Config(mode, Duration.ofSeconds(60));
        return new SendNotificationUseCase(
                mock(NotificationValidationService.class),
                idem,
                quota,
                path,
                db,
                cfg,
                Clock.systemUTC(),
                Duration.ofHours(24),
                Duration.ofSeconds(60));
    }

    private static SendNotificationRequest req(String content) {
        SendNotificationRequest r = new SendNotificationRequest();
        r.setChannel("email");
        r.setRecipient("a@b.com");
        r.setSubject("s");
        r.setContent(content);
        return r;
    }

    private static int status(Throwable t) {
        return ((BaseException) t).getStatus().value();
    }

    @Test
    void acceptsAndWritesOnce() {
        SendResult r = useCase.send(new SendCommand("t1", req("hello"), null));
        assertFalse(r.replayed());
        assertEquals("t1", r.response().getTenantId());
        assertEquals(1, db.count());
        assertEquals(1, quota.held());
        assertNull(db.rows.get(0).idempotencyKey());
    }

    @Test
    void sameKeySamePayloadReplaysOriginalResponse() {
        SendResult first = useCase.send(new SendCommand("t1", req("hello"), "k1"));
        SendResult second = useCase.send(new SendCommand("t1", req("hello"), "k1"));
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.response().getId(), second.response().getId());
        assertEquals(1, db.count());
        assertEquals(1, quota.held(), "replay must not consume quota again");
    }

    @Test
    void sameKeyDifferentPayloadIs422() {
        useCase.send(new SendCommand("t1", req("hello"), "k1"));
        BaseException e =
                assertThrows(
                        BaseException.class,
                        () -> useCase.send(new SendCommand("t1", req("DIFFERENT"), "k1")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), status(e));
        assertEquals(1, db.count());
    }

    @Test
    void keysAreScopedPerTenant() {
        useCase.send(new SendCommand("t1", req("hello"), "k1"));
        SendResult other = useCase.send(new SendCommand("t2", req("hello"), "k1"));
        assertFalse(other.replayed());
        assertEquals(2, db.count());
    }

    @Test
    void inFlightDuplicateIs409() {
        // simulate another request that claimed the key but has not finished
        idem.begin(
                "t1",
                "k1",
                org.example.notificationservice.domain.RequestFingerprint.of(
                        "email", "a@b.com", "s", "hello", null, null, "NORMAL"),
                "other-owner",
                Duration.ofSeconds(60));
        BaseException e =
                assertThrows(
                        BaseException.class,
                        () -> useCase.send(new SendCommand("t1", req("hello"), "k1")));
        assertEquals(HttpStatus.CONFLICT.value(), status(e));
        assertEquals(0, db.count());
    }

    @Test
    void sameKeyConcurrentNTimesCreatesExactlyOneNotification() throws Exception {
        int n = 64;
        List<Object> outcomes =
                runConcurrently(
                        n, () -> useCase.send(new SendCommand("t1", req("hello"), "storm")));
        assertEquals(1, db.count(), "exactly one notification for N concurrent identical requests");
        assertEquals(1, quota.held(), "exactly one quota unit held");
        long created =
                outcomes.stream().filter(o -> o instanceof SendResult s && !s.replayed()).count();
        assertEquals(1, created);
        Set<String> ids =
                outcomes.stream()
                        .filter(o -> o instanceof SendResult)
                        .map(o -> ((SendResult) o).response().getId())
                        .collect(Collectors.toSet());
        assertEquals(1, ids.size(), "everyone who got a response got the same notification id");
        // the rest got 409 (in flight)
        outcomes.stream()
                .filter(o -> o instanceof BaseException)
                .forEach(o -> assertEquals(409, status((BaseException) o)));
    }

    @Test
    void sameKeyConcurrentWithRedisDownStillExactlyOneBecauseDbIsArbiter() throws Exception {
        idem.down.set(true); // outbox mode: DB unique key arbitrates
        int n = 64;
        List<Object> outcomes =
                runConcurrently(
                        n, () -> useCase.send(new SendCommand("t1", req("hello"), "storm")));
        assertEquals(1, db.count());
        assertEquals(1, quota.held(), "losers released their reservations");
        Set<String> ids =
                outcomes.stream()
                        .filter(o -> o instanceof SendResult)
                        .map(o -> ((SendResult) o).response().getId())
                        .collect(Collectors.toSet());
        assertEquals(1, ids.size());
        assertEquals(n, outcomes.stream().filter(o -> o instanceof SendResult).count());
    }

    @Test
    void kafkaFirstFailsClosedWhenIdempotencyStoreIsDown() {
        SendNotificationUseCase kf = newUseCase(new KafkaFirstWritePath(n -> db.rows.add(n)));
        idem.down.set(true);
        assertThrows(
                DependencyUnavailableException.class,
                () -> kf.send(new SendCommand("t1", req("hello"), "k1")));
        assertEquals(0, db.count());
        assertEquals(0, quota.held());
    }

    @Test
    void quotaExceededIs429AndNothingIsWritten() {
        quota = new Fakes.Quota(2);
        useCase = newUseCase(new OutboxWritePath(db));
        useCase.send(new SendCommand("t1", req("1"), null));
        useCase.send(new SendCommand("t1", req("2"), null));
        BaseException e =
                assertThrows(
                        BaseException.class,
                        () -> useCase.send(new SendCommand("t1", req("3"), "kq")));
        assertEquals(429, status(e));
        assertEquals(2, db.count());
        assertEquals(0, idem.size(), "idempotency claim aborted so the client can retry");
    }

    @Test
    void parallelRequestsNeverExceedQuota() throws Exception {
        quota = new Fakes.Quota(25);
        useCase = newUseCase(new OutboxWritePath(db));
        AtomicInteger counter = new AtomicInteger();
        List<Object> outcomes =
                runConcurrently(
                        200,
                        () ->
                                useCase.send(
                                        new SendCommand(
                                                "t1", req("m" + counter.incrementAndGet()), null)));
        assertEquals(25, db.count());
        long accepted = outcomes.stream().filter(o -> o instanceof SendResult).count();
        assertEquals(25, accepted);
        outcomes.stream()
                .filter(o -> o instanceof BaseException)
                .forEach(o -> assertEquals(429, status((BaseException) o)));
    }

    @Test
    void quotaServiceDownFailsClosedWithoutWriting() {
        quota.down.set(true);
        assertThrows(
                DependencyUnavailableException.class,
                () -> useCase.send(new SendCommand("t1", req("hello"), "k1")));
        assertEquals(0, db.count());
        assertEquals(0, idem.size(), "claim aborted, client may retry with the same key");
        quota.down.set(false);
        assertFalse(useCase.send(new SendCommand("t1", req("hello"), "k1")).replayed());
    }

    @Test
    void writeFailureReleasesQuotaAndAbortsClaim() {
        db.failNext.set(true);
        assertThrows(
                IllegalStateException.class,
                () -> useCase.send(new SendCommand("t1", req("hello"), "k1")));
        assertEquals(0, quota.held());
        assertEquals(0, idem.size());
        SendResult retry = useCase.send(new SendCommand("t1", req("hello"), "k1"));
        assertFalse(retry.replayed());
        assertEquals(1, db.count());
    }

    @Test
    void contentHashFallbackDedupesWhenTenantEnablesIt() {
        mode = IdempotencyMode.HEADER_FIRST;
        SendResult a = useCase.send(new SendCommand("t1", req("same"), null));
        SendResult b = useCase.send(new SendCommand("t1", req("same"), null));
        SendResult c = useCase.send(new SendCommand("t1", req("other"), null));
        assertTrue(b.replayed());
        assertEquals(a.response().getId(), b.response().getId());
        assertFalse(c.replayed());
        assertNotEquals(a.response().getId(), c.response().getId());
        assertEquals(2, db.count());
        assertNull(db.rows.get(0).idempotencyKey(), "hash keys are Redis-only, never persisted");
    }

    @Test
    void defaultModeDoesNotDedupeWithoutHeader() {
        useCase.send(new SendCommand("t1", req("same"), null));
        useCase.send(new SendCommand("t1", req("same"), null));
        assertEquals(2, db.count());
    }

    /** Run n copies of the task at the same instant. Returns results or thrown BaseExceptions. */
    private static List<Object> runConcurrently(int n, Callable<SendResult> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 32));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    return task.call();
                                } catch (BaseException e) {
                                    return e;
                                }
                            }));
        }
        start.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) {
            out.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }
}
