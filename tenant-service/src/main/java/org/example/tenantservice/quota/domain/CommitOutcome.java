package org.example.tenantservice.quota.domain;

/** Result of durably counting one delivered notification. */
public enum CommitOutcome {
    COMMITTED,
    /** The same message id was already counted. */
    DUPLICATE,
    /** Counted as delivered but the limit was already reached; usage is capped at the limit. */
    LIMIT_REACHED,
    UNKNOWN_TENANT
}
