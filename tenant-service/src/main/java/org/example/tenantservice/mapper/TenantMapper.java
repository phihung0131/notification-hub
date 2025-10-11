package org.example.tenantservice.mapper;

import org.example.tenantservice.dto.response.TenantResponse;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.util.MapUtil;

public class TenantMapper {
    public static TenantResponse toDto(Tenant tenant) {
        TenantResponse response = new TenantResponse();
        MapUtil.copyProperties(tenant, response);
        return response;
    }
}
