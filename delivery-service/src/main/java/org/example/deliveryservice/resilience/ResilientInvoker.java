package org.example.deliveryservice.resilience;

import java.util.function.Supplier;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.provider.DeliveryException;
import org.springframework.stereotype.Component;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Per-channel circuit breaker + per-(channel, lane) bulkhead. The bulkhead rejects fast when a
 * provider hangs, so consumer threads are never all parked on one slow channel.
 */
@Component
public class ResilientInvoker {

    private final DeliveryProperties.Resilience cfg;
    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;

    public ResilientInvoker(DeliveryProperties props) {
        this.cfg = props.getResilience();
        this.breakers =
                CircuitBreakerRegistry.of(
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(cfg.getFailureRateThreshold())
                                .slidingWindowSize(cfg.getSlidingWindowSize())
                                .minimumNumberOfCalls(cfg.getMinimumCalls())
                                .waitDurationInOpenState(cfg.getOpenStateWait())
                                .permittedNumberOfCallsInHalfOpenState(cfg.getHalfOpenCalls())
                                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                                .recordException(ResilientInvoker::countsAsProviderFailure)
                                .build());
        this.bulkheads =
                BulkheadRegistry.of(
                        BulkheadConfig.custom()
                                .maxConcurrentCalls(cfg.getMaxConcurrentPerChannelLane())
                                .maxWaitDuration(cfg.getBulkheadMaxWait())
                                .build());
    }

    /** 429s and permanent 4xx say nothing about provider health. */
    static boolean countsAsProviderFailure(Throwable t) {
        if (t instanceof DeliveryException d) {
            return d.kind() == DeliveryException.Kind.RETRYABLE;
        }
        return true;
    }

    public CircuitBreaker breaker(String channel) {
        return breakers.circuitBreaker(channel);
    }

    public <T> T invoke(String channel, String lane, Supplier<T> call) {
        CircuitBreaker cb = breakers.circuitBreaker(channel);
        Bulkhead bh = bulkheads.bulkhead(channel + ":" + lane);
        try {
            return Bulkhead.decorateSupplier(bh, CircuitBreaker.decorateSupplier(cb, call)).get();
        } catch (CallNotPermittedException e) {
            throw DeliveryException.retryableAfter(
                    "circuit open for channel " + channel, cfg.getOpenStateWait());
        } catch (BulkheadFullException e) {
            throw DeliveryException.retryableAfter(
                    "bulkhead full for " + channel + ":" + lane, cfg.getBulkheadMaxWait());
        }
    }
}
