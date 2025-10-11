package org.example.tenantservice.mapper;

import org.example.tenantservice.dto.response.ApiKeyResponse;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.util.MapUtil;

public class ApiKeyMapper {
    public static ApiKeyResponse toDto(ApiKey entity) {
        ApiKeyResponse dto = new ApiKeyResponse();
        MapUtil.copyProperties(entity, dto);
        dto.setPermissions(entity.getPermissions());
        return dto;
    }
}
