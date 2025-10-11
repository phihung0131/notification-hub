package org.example.tenantservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.tenantservice.common.baseclass.ApiResponse;
import org.example.tenantservice.dto.request.PermissionCreateRequest;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.service.PermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * Public endpoint to get all API permissions.
     * This will be called by other services (e.g., Gateway) to fetch available permissions.
     */
    @GetMapping("/api")
    public ResponseEntity<ApiResponse<Set<Permission>>> getPermission() {
        Set<Permission> response = permissionService.getPermissionsByType();
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(response));
    }

    /**
     * Internal endpoint to create a new permission.
     * This will be called by Admin Service when setting up new permissions.
     * @param request Permission creation request
     * @return Created Permission entity
     */
    // TODO: Convert to event-driven (when other service have new permission, it will emit event to TenantService)
    @PostMapping
    public ResponseEntity<ApiResponse<Permission>> createPermission(@Valid @RequestBody PermissionCreateRequest request) {
        Permission permission = permissionService.createPermission(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(permission));
    }
}
