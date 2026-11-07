package org.example.notificationservice.application.port;

import org.example.notificationservice.domain.NewNotification;

public interface EventPublisherPort {

    /**
     * Publish notification.requested and wait for the broker ack (acks=all), keyed by tenantId.
     *
     * @throws org.example.notificationservice.domain.DependencyUnavailableException on failure
     */
    void publishSync(NewNotification notification);
}
