package org.example.tenantservice.model;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.*;

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
