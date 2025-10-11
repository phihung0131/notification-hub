package org.example.tenantservice.service;

import lombok.RequiredArgsConstructor;
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
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final PermissionRepository permissionRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final AuthService authService;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Create a new API key for the current tenant.
     * @param request the API key creation request
     * @return the created API key
     */
    // TODO: Hash the API key before returning it to the user
    public ApiKey createApiKey(ApiKeyCreateRequest request) {
        CustomUserDetails user = authService.getCurrentUser();
        Tenant tenant = tenantRepository.findById(user.getId()).orElseThrow(() -> new BaseException(ApiErrorMessage.TENANT_NOT_FOUND));

        ApiKey newApiKey = new ApiKey();
        newApiKey.setTenant(tenant);
        newApiKey.setExpiredAt(request.getExpiredAt());

        Set<Permission> permissions = new HashSet<>();
        for (String permissionId : request.getPermissionIds()) {
            Permission permission = permissionRepository.findById(permissionId).orElseThrow(() -> new BaseException(ApiErrorMessage.PERMISSION_NOT_FOUND));
            permissions.add(permission);
        }

        newApiKey.setPermissions(permissions);
        newApiKey.setRevoked(false);
        newApiKey.setKeyHash(generateApiKey());
        return apiKeyRepository.save(newApiKey);
    }

    /**
     * Revoke an existing API key.
     * @param apiKeyId the ID of the API key to revoke
     */
    public void revokeApiKey(String apiKeyId) {
        CustomUserDetails user = authService.getCurrentUser();
        ApiKey apiKey = apiKeyRepository.findById(apiKeyId).orElseThrow(() -> new BaseException(ApiErrorMessage.API_KEY_NOT_FOUND));

        if (!apiKey.getTenant().getId().equals(user.getId())) {
            throw new BaseException(ApiErrorMessage.FORBIDDEN);
        }

        apiKey.setRevoked(true);
        apiKeyRepository.save(apiKey);
    }

    /**
     * List all API keys for the current tenant.
     * @return a set of API keys
     */
    public Set<ApiKey> listApiKeys() {
        CustomUserDetails user = authService.getCurrentUser();
        return apiKeyRepository.findByTenantId(user.getId());
    }

    /**
     * Get details of a specific API key.
     * @param apiKeyId the ID of the API key
     * @return the API key
     */
    public ApiKey getApiKeyDetails(String apiKeyId) {
        CustomUserDetails user = authService.getCurrentUser();
        ApiKey apiKey = apiKeyRepository.findById(apiKeyId).orElseThrow(() -> new BaseException(ApiErrorMessage.API_KEY_NOT_FOUND));

        if (!apiKey.getTenant().getId().equals(user.getId())) {
            throw new BaseException(ApiErrorMessage.FORBIDDEN);
        }

        return apiKey;
    }

    /**
     * Update an existing API key's details.
     * @param apiKeyId the ID of the API key to update
     * @param request the API key update request
     * @return the updated API key
     */
    public ApiKey updateApiKey(String apiKeyId, ApiKeyCreateRequest request) {
        CustomUserDetails user = authService.getCurrentUser();
        ApiKey apiKey = apiKeyRepository.findById(apiKeyId).orElseThrow(() -> new BaseException(ApiErrorMessage.API_KEY_NOT_FOUND));

        if (!apiKey.getTenant().getId().equals(user.getId())) {
            throw new BaseException(ApiErrorMessage.FORBIDDEN);
        }

        if (request.getExpiredAt() != null) {
            apiKey.setExpiredAt(request.getExpiredAt());
        }

        if (request.getPermissionIds() != null && !request.getPermissionIds().isEmpty()) {
            Set<Permission> permissions = new HashSet<>();
            for (String permissionId : request.getPermissionIds()) {
                Permission permission = permissionRepository.findById(permissionId).orElseThrow(() -> new BaseException(ApiErrorMessage.PERMISSION_NOT_FOUND));
                permissions.add(permission);
            }
            apiKey.setPermissions(permissions);
        }

        return apiKeyRepository.save(apiKey);
    }

    /**
     * Generate a secure random API key.
     * @return the generated API key
     */
    public String generateApiKey() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
