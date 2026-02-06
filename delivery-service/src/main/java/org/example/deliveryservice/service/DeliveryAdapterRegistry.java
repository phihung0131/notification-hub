package org.example.deliveryservice.service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.example.deliveryservice.service.adapter.DeliveryAdapter;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Delivery Adapter Registry. Auto-registers all DeliveryAdapter beans via Spring dependency
 * injection. Follows Dependency Inversion Principle (DIP) - depends on abstraction (DeliveryAdapter
 * interface).
 */
@Component
@Slf4j
public class DeliveryAdapterRegistry {

    private final Map<String, DeliveryAdapter> adaptersByChannel;
    private final DeliveryAdapter defaultAdapter;

    /**
     * Constructor injection - Spring auto-discovers all DeliveryAdapter beans.
     *
     * @param adapters all DeliveryAdapter implementations
     */
    public DeliveryAdapterRegistry(List<DeliveryAdapter> adapters) {
        this.adaptersByChannel = new HashMap<>();

        DeliveryAdapter tempDefault = null;

        for (DeliveryAdapter adapter : adapters) {
            String channel = adapter.getChannel().toLowerCase(Locale.ROOT);
            adaptersByChannel.put(channel, adapter);
            log.info(
                    "Registered delivery adapter: {} -> {}",
                    channel,
                    adapter.getClass().getSimpleName());

            // Set default adapter
            if ("default".equals(channel)) {
                tempDefault = adapter;
            }
        }

        this.defaultAdapter = tempDefault;

        log.info("DeliveryAdapterRegistry initialized with {} adapters", adaptersByChannel.size());
    }

    /**
     * Resolve adapter by channel code.
     *
     * @param channel channel code (email, sms, telegram, etc.)
     * @return appropriate adapter or default adapter if not found
     */
    public DeliveryAdapter resolve(CharSequence channel) {
        if (channel == null) {
            log.warn("Null channel provided, using default adapter");
            return defaultAdapter;
        }

        String normalized = channel.toString().toLowerCase(Locale.ROOT);
        DeliveryAdapter adapter = adaptersByChannel.get(normalized);

        if (adapter == null) {
            log.warn("No adapter found for channel '{}', using default adapter", normalized);
            return defaultAdapter;
        }

        return adapter;
    }

    /**
     * Get all registered channel codes.
     *
     * @return set of channel codes
     */
    public Set<String> getRegisteredChannels() {
        return adaptersByChannel.keySet();
    }
}
