package com.example.deliveryservice.service.adapter;

import org.example.events.NotificationEvent;

import com.example.deliveryservice.service.model.DeliveryResult;

/** Strategy interface for channel-specific notification delivery. */
public interface DeliveryAdapter {

    /**
     * Delivers a notification via the channel-specific mechanism.
     *
     * @param event the notification event to deliver (never null)
     * @return delivery result with status and message
     * @throws RuntimeException if delivery fails (triggers retry logic)
     */
    DeliveryResult deliver(NotificationEvent event);

    /**
     * Returns the channel code this adapter handles.
     *
     * @return channel code in lowercase (e.g., "email", "sms", "telegram")
     */
    String getChannel();
}
