package org.example.notificationservice.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.example.notificationservice.common.enums.NotificationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Notification entity. Represents a notification request in PENDING status (saved before Kafka
 * publish).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "notifications",
        indexes = {
            @Index(name = "idx_tenant_id", columnList = "tenantId"),
            @Index(name = "idx_status", columnList = "status"),
            @Index(name = "idx_created_at", columnList = "createdAt")
        })
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String recipient;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.PENDING;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** Client Idempotency-Key; unique per tenant while set (cleared after 24h). */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /** SHA-256 of the canonical request payload (detects key reuse with another payload). */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String priority = "NORMAL";

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
