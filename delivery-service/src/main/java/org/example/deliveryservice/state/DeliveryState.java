package org.example.deliveryservice.state;

/** Delivery lifecycle. Higher rank wins; rank 2 states are terminal and never overwritten. */
public enum DeliveryState {
    IN_PROGRESS(0),
    SENT(1),
    DELIVERED(2),
    BOUNCED(2),
    FAILED(2);

    private final int rank;

    DeliveryState(int rank) {
        this.rank = rank;
    }

    /**
     * Status published on notification.result. The Avro enum has no DELIVERED/BOUNCED, so delivered
     * maps to ACKNOWLEDGED and bounced to FAILED.
     */
    public org.example.events.enums.NotificationStatus toEventStatus() {
        return switch (this) {
            case IN_PROGRESS -> org.example.events.enums.NotificationStatus.PENDING;
            case SENT -> org.example.events.enums.NotificationStatus.SENT;
            case DELIVERED -> org.example.events.enums.NotificationStatus.ACKNOWLEDGED;
            case BOUNCED, FAILED -> org.example.events.enums.NotificationStatus.FAILED;
        };
    }

    public int rank() {
        return rank;
    }
}
