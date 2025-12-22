package org.example.tenantservice.dto.response;

import java.util.Set;

import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.model.Permission;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenantResponse {
    private String id;
    private String name;
    private Plan plan;
    private String email;
    private Integer quotaLimit = 0;
    private Integer quotaUsed = 0;
    private Set<Permission> permissions;
}
