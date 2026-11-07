package org.example.notificationservice.application.port;

import java.time.Duration;

/**
 * Fast-path idempotency store (Redis). Postgres unique (tenant_id, idempotency_key) remains the
 * final arbiter.
 */
public interface IdempotencyPort {

    enum Kind {
        /** First caller: go ahead, you own the key until complete/abort. */
        PROCEED,
        /** Same key + same payload already finished: replay {@code notificationId}. */
        REPLAY,
        /** Same key + same payload still being processed by another request. */
        IN_FLIGHT,
        /** Same key but a different payload. */
        MISMATCH
    }

    record Begin(Kind kind, String notificationId) {}

    /**
     * Atomically claim the key (SET NX). {@code owner} identifies this attempt.
     *
     * @throws RuntimeException when the store is unreachable
     */
    Begin begin(
            String tenantId, String key, String fingerprint, String owner, Duration inFlightTtl);

    /** Mark done and keep the stored response for {@code ttl}. */
    void complete(
            String tenantId, String key, String fingerprint, String notificationId, Duration ttl);

    /** Release an in-flight claim (only if still owned) so the client can retry. */
    void abort(String tenantId, String key, String fingerprint, String owner);
}
