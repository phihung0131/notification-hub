package org.example.notificationservice.model;

import jakarta.persistence.*;

import lombok.*;

/** Channel entity. Represents a notification channel (email, sms, telegram, etc.) */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "channels")
public class Channel {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String code; // email, sms, telegram

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
