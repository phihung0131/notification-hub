package org.example.deliveryservice.provider;

import java.time.Duration;

/** Failure raised by a channel provider; carries retry classification. */
public class DeliveryException extends RuntimeException {

    public enum Kind {
        RETRYABLE,
        RATE_LIMITED,
        PERMANENT
    }

    private final Kind kind;
    private final Duration retryAfter;

    private DeliveryException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public static DeliveryException retryable(String message, Throwable cause) {
        return new DeliveryException(Kind.RETRYABLE, message, null, cause);
    }

    public static DeliveryException retryableAfter(String message, Duration retryAfter) {
        return new DeliveryException(Kind.RETRYABLE, message, retryAfter, null);
    }

    public static DeliveryException rateLimited(String message, Duration retryAfter) {
        return new DeliveryException(Kind.RATE_LIMITED, message, retryAfter, null);
    }

    public static DeliveryException permanent(String message, Throwable cause) {
        return new DeliveryException(Kind.PERMANENT, message, null, cause);
    }

    public Kind kind() {
        return kind;
    }

    /** Provider/breaker supplied minimum wait before retrying, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean isPermanent() {
        return kind == Kind.PERMANENT;
    }
}
