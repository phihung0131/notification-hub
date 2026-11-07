package org.example.deliveryservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.provider.ChannelProvider;
import org.example.deliveryservice.provider.ChannelProvider.ProviderResult;
import org.example.deliveryservice.provider.DeliveryException;
import org.example.deliveryservice.resilience.ResilientInvoker;
import org.example.events.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChannelDispatcher failover and 429")
class ChannelDispatcherTest {

    private final AtomicInteger pushCalls = new AtomicInteger();
    private DeliveryProperties props;
    private ChannelDispatcher dispatcher;
    private final NotificationEvent event =
            NotificationEvent.newBuilder().setChannel("sms").setRecipient("r").build();

    private static ChannelProvider provider(
            String ch, java.util.function.Supplier<ProviderResult> s) {
        return new ChannelProvider() {
            public String channel() {
                return ch;
            }

            public ProviderResult send(NotificationEvent e, String k) {
                return s.get();
            }
        };
    }

    private void build(ChannelProvider sms) {
        ChannelProvider push =
                provider(
                        "push",
                        () -> {
                            pushCalls.incrementAndGet();
                            return new ProviderResult("push-1", "ok");
                        });
        props = new DeliveryProperties();
        DeliveryProperties.Provider cfg = new DeliveryProperties.Provider();
        cfg.setFailoverChannel("push");
        props.getProviders().put("sms", cfg);
        dispatcher =
                new ChannelDispatcher(
                        new ChannelProviderRegistry(List.of(sms, push)),
                        new ResilientInvoker(props),
                        props);
    }

    @BeforeEach
    void reset() {
        pushCalls.set(0);
    }

    @Test
    @DisplayName("retryable failure fails over to the configured channel")
    void failover() {
        build(
                provider(
                        "sms",
                        () -> {
                            throw DeliveryException.retryable("down", (Throwable) null);
                        }));

        ProviderResult r = dispatcher.dispatch(event, "k", "normal");

        assertEquals("push-1", r.providerMessageId());
        assertEquals(1, pushCalls.get());
    }

    @Test
    @DisplayName("429 is not failed over; Retry-After is preserved for the retry router")
    void rateLimitNoFailover() {
        build(
                provider(
                        "sms",
                        () -> {
                            throw DeliveryException.rateLimited("429", Duration.ofSeconds(9));
                        }));

        DeliveryException e =
                assertThrows(
                        DeliveryException.class, () -> dispatcher.dispatch(event, "k", "normal"));

        assertEquals(Duration.ofSeconds(9), e.retryAfter());
        assertEquals(0, pushCalls.get());
    }

    @Test
    @DisplayName("permanent failure is not failed over")
    void permanentNoFailover() {
        build(
                provider(
                        "sms",
                        () -> {
                            throw DeliveryException.permanent("bad", null);
                        }));

        assertThrows(DeliveryException.class, () -> dispatcher.dispatch(event, "k", "normal"));
        assertEquals(0, pushCalls.get());
    }
}
