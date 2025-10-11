package org.example.tenantservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.example.tenantservice.common.enums.Plan;

import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Plan plan;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    private Integer quotaLimit = 0;    // -1 unlimited
    private Integer quotaUsed = 0;     // cached snapshot (optional)

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "tenant_permissions",
            joinColumns = @JoinColumn(name = "tenant_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions;
}
