package org.example.tenantservice.unit.service;

import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.common.exception.BaseException;
import org.example.tenantservice.config.security.CustomUserDetails;
import org.example.tenantservice.dto.request.ApiKeyCreateRequest;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.PermissionRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.example.tenantservice.service.ApiKeyService;
import org.example.tenantservice.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    PermissionRepository permissionRepository;

    @Mock
    ApiKeyRepository apiKeyRepository;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    AuthService authService;

    @InjectMocks
    ApiKeyService apiKeyService;

    CustomUserDetails currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new CustomUserDetails();
        currentUser.setId("tenant-1");
        currentUser.setEmail("t@t.com");
    }

    @Test
    void createApiKey_whenOk_thenReturnsSaved() {
        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now().plusSeconds(3600));
        req.setPermissionIds(Set.of("perm-1"));

        Tenant tenant = Tenant.builder().id("tenant-1").email("t@t.com").build();
        Permission perm = new Permission();
        perm.setId("perm-1");
        perm.setName("READ");

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(tenantRepository.findById("tenant-1")).thenReturn(Optional.of(tenant));
        when(permissionRepository.findById("perm-1")).thenReturn(Optional.of(perm));

        ApiKey saved = new ApiKey();
        saved.setId("apikey-1");
        when(apiKeyRepository.save(any())).thenReturn(saved);

        ApiKey result = apiKeyService.createApiKey(req);

        assertNotNull(result);
        assertEquals("apikey-1", result.getId());
        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    void createApiKey_whenTenantNotFound_thenThrow() {
        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now());
        req.setPermissionIds(Set.of());

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(tenantRepository.findById("tenant-1")).thenReturn(Optional.empty());

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.createApiKey(req));
        assertEquals(ApiErrorMessage.TENANT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void createApiKey_whenPermissionNotFound_thenThrow() {
        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now());
        req.setPermissionIds(Set.of("perm-x"));

        Tenant tenant = Tenant.builder().id("tenant-1").email("t@t.com").build();
        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(tenantRepository.findById("tenant-1")).thenReturn(Optional.of(tenant));
        when(permissionRepository.findById("perm-x")).thenReturn(Optional.empty());

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.createApiKey(req));
        assertEquals(ApiErrorMessage.PERMISSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void revokeApiKey_whenSuccess_thenRevokedAndSaved() {
        ApiKey apiKey = new ApiKey();
        Tenant tenant = Tenant.builder().id("tenant-1").build();
        apiKey.setId("k1");
        apiKey.setTenant(tenant);
        apiKey.setRevoked(false);

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(any())).thenReturn(apiKey);

        apiKeyService.revokeApiKey("k1");

        assertTrue(apiKey.isRevoked());
        verify(apiKeyRepository).save(apiKey);
    }

    @Test
    void revokeApiKey_whenNotFound_thenThrow() {
        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.empty());

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.revokeApiKey("k1"));
        assertEquals(ApiErrorMessage.API_KEY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void revokeApiKey_whenForbidden_thenThrow() {
        ApiKey apiKey = new ApiKey();
        Tenant tenant = Tenant.builder().id("other-tenant").build();
        apiKey.setId("k1");
        apiKey.setTenant(tenant);

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.of(apiKey));

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.revokeApiKey("k1"));
        assertEquals(ApiErrorMessage.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void listApiKeys_returnsSet() {
        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findByTenantId("tenant-1")).thenReturn(Set.of(new ApiKey()));

        Set<ApiKey> keys = apiKeyService.listApiKeys();
        assertNotNull(keys);
        assertEquals(1, keys.size());
    }

    @Test
    void getApiKeyDetails_whenSuccess_thenReturn() {
        ApiKey apiKey = new ApiKey();
        Tenant tenant = Tenant.builder().id("tenant-1").build();
        apiKey.setId("k1");
        apiKey.setTenant(tenant);

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.of(apiKey));

        ApiKey res = apiKeyService.getApiKeyDetails("k1");
        assertSame(apiKey, res);
    }

    @Test
    void getApiKeyDetails_whenForbidden_thenThrow() {
        ApiKey apiKey = new ApiKey();
        Tenant tenant = Tenant.builder().id("other").build();
        apiKey.setId("k1");
        apiKey.setTenant(tenant);

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.of(apiKey));

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.getApiKeyDetails("k1"));
        assertEquals(ApiErrorMessage.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void getApiKeyDetails_whenNotFound_thenThrow() {
        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.empty());

        BaseException ex = assertThrows(BaseException.class, () -> apiKeyService.getApiKeyDetails("k1"));
        assertEquals(ApiErrorMessage.API_KEY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateApiKey_whenSuccess_thenUpdated() {
        ApiKey existing = new ApiKey();
        Tenant tenant = Tenant.builder().id("tenant-1").build();
        existing.setId("k1");
        existing.setTenant(tenant);

        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now().plusSeconds(1000));
        req.setPermissionIds(Set.of("p1"));

        Permission p1 = new Permission();
        p1.setId("p1");

        when(authService.getCurrentUser()).thenReturn(currentUser);
        when(apiKeyRepository.findById("k1")).thenReturn(Optional.of(existing));
        when(permissionRepository.findById("p1")).thenReturn(Optional.of(p1));
        when(apiKeyRepository.save(any())).thenReturn(existing);

        ApiKey out = apiKeyService.updateApiKey("k1", req);
        assertNotNull(out);
        verify(apiKeyRepository).save(existing);
    }

    @Test
    void generateApiKey_returnsNonEmpty() {
        String k = apiKeyService.generateApiKey();
        assertNotNull(k);
        assertFalse(k.isEmpty());
    }

}

