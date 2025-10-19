package org.example.tenantservice.unit;

import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.common.exception.BaseException;
import org.example.tenantservice.dto.request.PermissionCreateRequest;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.repository.PermissionRepository;
import org.example.tenantservice.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    PermissionRepository permissionRepository;

    @InjectMocks
    PermissionService permissionService;

    @Test
    void createPermission_whenAlreadyExists_thenThrow() {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setName("READ");
        req.setType(PermissionType.API);

        when(permissionRepository.findByNameAndType(req.getName(), req.getType())).thenReturn(Optional.of(new Permission()));

        BaseException ex = assertThrows(BaseException.class, () -> permissionService.createPermission(req));
        assertEquals(ApiErrorMessage.PERMISSION_ALREADY_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void createPermission_whenOk_thenSaveAndReturn() {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setName("WRITE");
        req.setType(PermissionType.API);
        req.setDescription("desc");

        Permission saved = new Permission();
        saved.setId("p1");
        saved.setName(req.getName());
        saved.setType(req.getType());
        saved.setDescription(req.getDescription());

        when(permissionRepository.findByNameAndType(req.getName(), req.getType())).thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenReturn(saved);

        Permission out = permissionService.createPermission(req);
        assertNotNull(out);
        assertEquals("p1", out.getId());
        verify(permissionRepository).save(any(Permission.class));
    }

    @Test
    void getPermissionsByType_returnsSet() {
        when(permissionRepository.findByType(PermissionType.API)).thenReturn(Set.of(new Permission()));

        Set<Permission> out = permissionService.getPermissionsByType();
        assertNotNull(out);
        assertEquals(1, out.size());
    }

}

