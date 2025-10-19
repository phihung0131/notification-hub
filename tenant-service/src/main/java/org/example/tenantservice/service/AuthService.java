package org.example.tenantservice.service;

import lombok.RequiredArgsConstructor;
import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.common.exception.BaseException;
import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.config.security.CustomUserDetails;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.example.tenantservice.common.exception.ApiErrorMessage.EMAIL_NOT_AVAILABLE;
import static org.example.tenantservice.common.exception.ApiErrorMessage.INVALID_API_KEY;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;


    /**
     * Authenticate user and generate JWT token
     * @param email Email of the user
     * @param password Password of the user
     * @return JWT token
     */
    public String loginUser(String email, String password) {
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(email, password);
        
        Authentication authentication = authenticationManager.authenticate(authenticationToken);
        
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return jwtUtils.generateJwtToken(authentication);
    }

    /**
     * Validate the provided API key and return its associated permissions.
     * @param rawApiKey the raw API key to validate
     * @return a set of permission names associated with the valid API key
     */
    // TODO: Implement caching for API keys and permissions
    public Set<String> validateApiKeyAndGetPermissions(String rawApiKey) {
        List<ApiKey> allApiKeys = apiKeyRepository.findAll();

        for (ApiKey apiKey : allApiKeys) {
            if (!apiKey.isRevoked() && passwordEncoder.matches(rawApiKey, apiKey.getKey())) {
                // If API key is valid, return its permissions
                return apiKey.getPermissions().stream()
                        .filter(p -> p.getType() == PermissionType.API)
                        .map(Permission::getName)
                        .collect(Collectors.toSet());
            }
        }

        // Error if no valid API key found
        throw new BaseException(INVALID_API_KEY);
    }

    /**
     * Register a new tenant
     * @param tenantCreateRequest the tenant creation request
     * @return the created tenant
     */
    public Tenant registerNewTenant(TenantCreateRequest tenantCreateRequest) {
        if (tenantRepository.findByEmail(tenantCreateRequest.getEmail()).isPresent()) {
            throw new BaseException(EMAIL_NOT_AVAILABLE);
        }

        Tenant tenant = Tenant.builder()
                .name(tenantCreateRequest.getName())
                .email(tenantCreateRequest.getEmail())
                .quotaLimit(1000) // TODO: No hardcode
                .quotaUsed(0)
                .plan(Plan.FREE)
                .password(passwordEncoder.encode(tenantCreateRequest.getPassword()))
                .permissions(new HashSet<>())
                .build();

        return tenantRepository.save(tenant);
    }

    /**
     * Get the currently authenticated user
     * @return the current user details
     */
    public CustomUserDetails getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BaseException(ApiErrorMessage.UNAUTHORIZED);
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof CustomUserDetails userDetails) {
            return userDetails;
        }
        throw new BaseException(ApiErrorMessage.UNAUTHORIZED);
    }
}
