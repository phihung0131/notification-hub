package org.example.notificationservice.domain;

/** The (tenant, idempotency key) unique constraint rejected an insert: someone else won. */
public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
