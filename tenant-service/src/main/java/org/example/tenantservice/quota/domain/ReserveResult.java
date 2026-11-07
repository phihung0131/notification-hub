package org.example.tenantservice.quota.domain;

/** Outcome of an atomic quota reservation. */
public enum ReserveResult {
    GRANTED,
    EXCEEDED,
    UNKNOWN_TENANT
}
