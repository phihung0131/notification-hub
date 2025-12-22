package org.example.tenantservice.dto;

import java.io.Serializable;

import org.example.tenantservice.common.enums.Plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tenant information DTO for caching. Implements Serializable for Redis storage. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantInfo implements Serializable {
    private String tenantId;
    private String status;
    private Plan plan;
    private Integer quotaLimit;
    private Integer quotaUsed;
}
