package org.example.notificationservice.domain;

/** Per-tenant idempotency policy. */
public enum IdempotencyMode {
    /** Use the Idempotency-Key header when present; no content-hash fallback (default). */
    HEADER_ONLY,
    /** Header when present, otherwise dedupe by content hash within a short window. */
    HEADER_FIRST
}
