package org.example.tenantservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String keyHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    private boolean revoked; // Đã bị thu hồi chưa?
    private Instant expiredAt; // Thời gian hết hạn (nếu có)

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "apikey_permissions",
            joinColumns = @JoinColumn(name = "apikey_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions;
}
