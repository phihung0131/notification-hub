package org.example.deliveryservice.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.example.deliveryservice.provider.ChannelProvider;

import lombok.extern.slf4j.Slf4j;

/** Resolves the provider for a channel code; unknown channels use the "default" provider. */
@Slf4j
public class ChannelProviderRegistry {

    private final Map<String, ChannelProvider> byChannel = new HashMap<>();

    public ChannelProviderRegistry(Collection<ChannelProvider> providers) {
        for (ChannelProvider p : providers) {
            byChannel.put(p.channel().toLowerCase(Locale.ROOT), p);
            log.info(
                    "Registered channel provider {} -> {}",
                    p.channel(),
                    p.getClass().getSimpleName());
        }
    }

    /** Provider for the channel, the default provider if unknown, or null if neither exists. */
    public ChannelProvider resolve(CharSequence channel) {
        if (channel == null) {
            return byChannel.get("default");
        }
        ChannelProvider p = byChannel.get(channel.toString().toLowerCase(Locale.ROOT));
        if (p == null) {
            log.warn("No provider for channel '{}', using default", channel);
            return byChannel.get("default");
        }
        return p;
    }

    /** Exact lookup without default fallback (used for failover). */
    public ChannelProvider find(String channel) {
        return channel == null ? null : byChannel.get(channel.toLowerCase(Locale.ROOT));
    }

    public Set<String> registeredChannels() {
        return byChannel.keySet();
    }
}
