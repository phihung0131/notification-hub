package org.example.deliveryservice.provider;

import org.example.events.NotificationEvent;

/** Port to an external delivery provider (SMTP server, SMS gateway, push service...). */
public interface ChannelProvider {

    /** Channel code handled, lowercase (email, sms, push, telegram, default). */
    String channel();

    /**
     * Send the notification.
     *
     * @param idempotencyKey stable per notification; providers that support it dedupe on it
     * @throws DeliveryException classified failure
     */
    ProviderResult send(NotificationEvent event, String idempotencyKey);

    record ProviderResult(String providerMessageId, String detail) {}
}
