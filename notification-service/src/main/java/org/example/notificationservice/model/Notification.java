package org.example.notificationservice.model;

import jakarta.persistence.*;
import lombok.Data;
import org.example.notificationservice.common.enums.NotificationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String tenantId;
    private String apiKey;
    private String recipient;
    private String subject;
    private String content;

    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    private Channel channel;
}
