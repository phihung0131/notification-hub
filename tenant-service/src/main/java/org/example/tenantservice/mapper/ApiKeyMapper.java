package org.example.tenantservice.mapper;

import org.example.commons.util.MapUtil;
import org.example.tenantservice.dto.response.ApiKeyResponse;
import org.example.tenantservice.model.ApiKey;

public class ApiKeyMapper {
    public static ApiKeyResponse toDto(ApiKey entity) {
        ApiKeyResponse dto = new ApiKeyResponse();
        MapUtil.copyProperties(entity, dto);
        dto.setPermissions(entity.getPermissions());
        return dto;
    }
}
