package org.example.deliveryservice.service;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.provider.ChannelProvider;
import org.example.deliveryservice.provider.ChannelProvider.ProviderResult;
import org.example.deliveryservice.provider.DeliveryException;
import org.example.deliveryservice.resilience.ResilientInvoker;
import org.example.events.NotificationEvent;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Sends through the channel provider under breaker/bulkhead, with optional failover channel. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelDispatcher {

    private final ChannelProviderRegistry registry;
    private final ResilientInvoker invoker;
    private final DeliveryProperties props;

    public ProviderResult dispatch(NotificationEvent event, String idempotencyKey, String lane) {
        ChannelProvider primary = registry.resolve(event.getChannel());
        if (primary == null) {
            throw DeliveryException.permanent(
                    "no provider for channel " + event.getChannel(), null);
        }
        try {
            return call(primary, event, idempotencyKey, lane);
        } catch (DeliveryException e) {
            // rate limiting is the provider asking us to wait, not a reason to switch channel
            if (e.kind() == DeliveryException.Kind.RETRYABLE) {
                ChannelProvider failover = failoverFor(primary.channel());
                if (failover != null) {
                    log.warn(
                            "Channel {} failed ({}), failing over to {}",
                            primary.channel(),
                            e.getMessage(),
                            failover.channel());
                    return call(failover, event, idempotencyKey, lane);
                }
            }
            throw e;
        }
    }

    private ProviderResult call(
            ChannelProvider provider, NotificationEvent event, String key, String lane) {
        return invoker.invoke(provider.channel(), lane, () -> provider.send(event, key));
    }

    private ChannelProvider failoverFor(String channel) {
        DeliveryProperties.Provider cfg = props.getProviders().get(channel);
        if (cfg == null || cfg.getFailoverChannel() == null) {
            return null;
        }
        ChannelProvider p = registry.find(cfg.getFailoverChannel());
        return p == null || p.channel().equals(channel) ? null : p;
    }
}
