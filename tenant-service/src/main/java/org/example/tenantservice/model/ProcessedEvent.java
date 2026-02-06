package org.example.tenantservice.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Processed Event tracking for idempotency. Ensures we don't process the same Kafka event multiple
 * times.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "processed_events",
        indexes = {
            @Index(name = "idx_message_id", columnList = "messageId"),
            @Index(name = "idx_processed_at", columnList = "processedAt")
        })
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String messageId;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String eventType; // NOTIFICATION_RESULT

    @Column(nullable = false)
    @Builder.Default
    private Instant processedAt = Instant.now();
}
