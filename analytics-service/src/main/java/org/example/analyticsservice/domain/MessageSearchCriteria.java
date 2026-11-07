package org.example.analyticsservice.domain;

import java.time.Instant;

import org.example.events.enums.NotificationStatus;

/**
 * Tenant-scoped filters; all but tenantId optional. {@code from} inclusive, {@code to} exclusive.
 */
public record MessageSearchCriteria(
        String tenantId, NotificationStatus status, String channel, Instant from, Instant to) {}
