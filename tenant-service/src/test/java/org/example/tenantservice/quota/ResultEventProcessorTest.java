package org.example.tenantservice.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;

import org.example.tenantservice.quota.application.QuotaReservationService;
import org.example.tenantservice.quota.application.ResultEventProcessor;
import org.example.tenantservice.quota.domain.ReserveResult;
import org.example.tenantservice.quota.domain.ResultEvent;
import org.example.tenantservice.quota.domain.ResultEvent.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultEventProcessorTest {

    private InMemoryQuota.Ledger ledger;
    private InMemoryQuota.Store store;
    private QuotaReservationService quota;
    private ResultEventProcessor processor;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryQuota.Ledger();
        store = new InMemoryQuota.Store();
        quota = new QuotaReservationService(ledger, store, Duration.ofMinutes(15));
        processor = new ResultEventProcessor(quota);
        store.tenant("t1", 100, 0);
    }

    private static ResultEvent sent(String id) {
        return new ResultEvent(id, "t1", Outcome.SENT);
    }

    @Test
    void duplicateResultEventsAreCountedOnce() {
        quota.reserve("t1", "m1", null);
        // same event three times in one batch, then redelivered in later batches
        processor.process(List.of(sent("m1"), sent("m1"), sent("m1")));
        processor.process(List.of(sent("m1")));
        processor.process(List.of(sent("m1"), sent("m2")));
        assertEquals(2, store.used("t1"));
    }

    @Test
    void failedEventReleasesTheHoldWithoutCounting() {
        quota.reserve("t1", "m1", null);
        processor.process(List.of(new ResultEvent("m1", "t1", Outcome.FAILED)));
        assertEquals(0, store.used("t1"));
        assertEquals(0, ledger.snapshot("t1").orElseThrow().held());
    }

    @Test
    void sentWinsOverFailedForSameIdInBatch() {
        quota.reserve("t1", "m1", null);
        processor.process(List.of(new ResultEvent("m1", "t1", Outcome.FAILED), sent("m1")));
        assertEquals(1, store.used("t1"));
    }

    @Test
    void malformedEventsAreSkipped() {
        processor.process(
                java.util.Arrays.asList(
                        null,
                        new ResultEvent(null, "t1", Outcome.SENT),
                        new ResultEvent("x", null, Outcome.SENT),
                        sent("ok")));
        assertEquals(1, store.used("t1"));
    }

    @Test
    void dbFailureDoesNotAckAndRedeliveryIsCountedExactlyOnce() {
        quota.reserve("t1", "m1", null);
        store.failNextCommit.set(true);
        assertThrows(RuntimeException.class, () -> processor.process(List.of(sent("m1"))));
        assertEquals(0, store.used("t1"), "nothing durable when the DB write failed");
        // container redelivers the whole batch
        processor.process(List.of(sent("m1")));
        assertEquals(1, store.used("t1"));
    }

    @Test
    void crashBetweenDbCommitAndRedisSettleHealsOnRedelivery() {
        quota.reserve("t1", "m1", null);
        ledger.down.set(true); // DB write succeeds, ledger settle fails -> no ack
        assertThrows(RuntimeException.class, () -> processor.process(List.of(sent("m1"))));
        assertEquals(1, store.used("t1"), "durable count is already there");
        ledger.down.set(false);
        processor.process(List.of(sent("m1"))); // redelivery: DUPLICATE in DB, ledger settled
        assertEquals(1, store.used("t1"), "not double counted");
        assertEquals(0, ledger.snapshot("t1").orElseThrow().held());
        assertEquals(1, ledger.snapshot("t1").orElseThrow().used());
    }

    @Test
    void committedUnitsStopFurtherReservationsAtTheLimit() {
        store.tenant("t2", 2, 0);
        for (String id : List.of("a", "b")) {
            quota.reserve("t2", id, null);
        }
        processor.process(
                List.of(
                        new ResultEvent("a", "t2", Outcome.SENT),
                        new ResultEvent("b", "t2", Outcome.SENT)));
        assertEquals(ReserveResult.EXCEEDED, quota.reserve("t2", "c", null).result());
    }
}
