package org.example.tenantservice.service;

import lombok.RequiredArgsConstructor;
import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.common.exception.BaseException;
import org.example.tenantservice.dto.request.PermissionCreateRequest;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.repository.PermissionRepository;
import org.example.tenantservice.util.MapUtil;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepository;

    /**
     * Create a new permission
     * @param request the permission creation request
     * @return the created permission
     */
    public Permission createPermission(PermissionCreateRequest request) {
        if (permissionRepository.findByNameAndType(request.getName(), request.getType()).isPresent()) {
            throw new BaseException(ApiErrorMessage.PERMISSION_ALREADY_EXISTS);
        }

        Permission entity = new Permission();
        MapUtil.copyProperties(request, entity);
        return permissionRepository.save(entity);
    }

    /**
     * Get all permissions of type API
     * @return set of permissions
     */
    public Set<Permission> getPermissionsByType() {
        return permissionRepository.findByType(PermissionType.API);
    }
}
