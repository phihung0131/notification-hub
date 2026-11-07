package org.example.deliveryservice.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.RetryRouter.Route;
import org.example.deliveryservice.provider.DeliveryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

@DisplayName("RetryRouter tiers")
class RetryRouterTest {

    private final RetryRouter router =
            new RetryRouter(new DeliveryProperties(), "notification.dlq");

    private static Exception retryable() {
        return new ListenerExecutionFailedException(
                "x", DeliveryException.retryable("503", (Throwable) null));
    }

    @Test
    void firstFailureGoesToFirstTier() {
        Route r = router.route(0, retryable());
        assertEquals("notification.requested.retry.5s", r.topic());
        assertEquals(1, r.attempt());
        assertFalse(r.dlq());
    }

    @Test
    void escalatesThroughTiersThenDlq() {
        assertEquals("notification.requested.retry.30s", router.route(1, retryable()).topic());
        assertEquals("notification.requested.retry.2m", router.route(2, retryable()).topic());
        Route last = router.route(3, retryable());
        assertTrue(last.dlq());
        assertEquals("notification.dlq", last.topic());
        assertEquals(4, last.attempt());
    }

    @Test
    void permanentFailureSkipsRetries() {
        Route r = router.route(0, DeliveryException.permanent("bad number", null));
        assertTrue(r.dlq());
        assertEquals("bad number", r.reason());
    }

    @Test
    void rateLimitRetryAfterPicksCoveringTier() {
        Route r = router.route(0, DeliveryException.rateLimited("429", Duration.ofSeconds(20)));
        assertEquals("notification.requested.retry.30s", r.topic());
        assertEquals(2, r.attempt());
    }

    @Test
    void hugeRetryAfterClampsToLastTier() {
        Route r = router.route(0, DeliveryException.rateLimited("429", Duration.ofHours(1)));
        assertEquals("notification.requested.retry.2m", r.topic());
    }

    @Test
    void rateLimitNeverMovesBackwards() {
        Route r = router.route(2, DeliveryException.rateLimited("429", Duration.ofSeconds(1)));
        assertEquals("notification.requested.retry.2m", r.topic());
    }
}
