package org.example.tenantservice.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.model.Permission;

import java.util.Set;

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
