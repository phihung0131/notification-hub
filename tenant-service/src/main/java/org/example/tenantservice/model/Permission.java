package org.example.tenantservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.tenantservice.common.enums.PermissionType;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"name", "type"})
})
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(length = 50, nullable = false)
    private String name; // e.g., "dashboard:view", "user:invite", "notification:send"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionType type;

    private String description;
}