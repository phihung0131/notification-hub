package org.example.tenantservice.model;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.*;

import lombok.*;

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

    @Column(name = "api_key_value", unique = true, nullable = false)
    private String key;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    private boolean revoked; // revocation status
    private Instant expiredAt; // expiration time

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "apikey_permissions",
            joinColumns = @JoinColumn(name = "apikey_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions;
}
