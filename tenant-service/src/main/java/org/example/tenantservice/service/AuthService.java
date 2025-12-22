package org.example.tenantservice.service;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.example.commons.exception.BaseException;
import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.config.security.CustomUserDetails;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.dto.response.ApiKeyValidationResponse;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Service responsible for authentication, authorization, and tenant registration.
 *
 * <p>This service handles:
 *
 * <ul>
 *   <li>User authentication with JWT token generation
 *   <li>API key validation and permission retrieval
 *   <li>New tenant registration with password encoding
 *   <li>Current user context retrieval
 * </ul>
 *
 * <h2>Security Features:</h2>
 *
 * <ul>
 *   <li><strong>Password Encoding:</strong> Uses BCrypt for secure password storage
 *   <li><strong>JWT Generation:</strong> Creates signed tokens for authenticated sessions
 *   <li><strong>API Key Caching:</strong> Validates API keys with Redis caching (cache name:
 *       apiKeyPermissions)
 *   <li><strong>Permission Filtering:</strong> Returns only API-type permissions for API key
 *       validation
 * </ul>
 *
 * <h2>Authentication Flow:</h2>
 *
 * <ol>
 *   <li>Client sends email + password to /auth/login
 *   <li>AuthService authenticates via AuthenticationManager (Spring Security)
 *   <li>On success: Generate JWT token and set SecurityContext
 *   <li>Return JWT to client for subsequent requests
 * </ol>
 *
 * <h2>API Key Validation Flow:</h2>
 *
 * <ol>
 *   <li>Gateway sends API key to /auth/internal/apikeys/validate
 *   <li>Check Redis cache for previous validation (TTL-based)
 *   <li>If miss: Query database for API key
 *   <li>Verify not revoked
 *   <li>Extract tenant ID and API-type permissions
 *   <li>Cache result and return to Gateway
 * </ol>
 *
 * <h2>Registration Flow:</h2>
 *
 * <ol>
 *   <li>Check email uniqueness
 *   <li>Encode password with BCrypt
 *   <li>Create tenant with default quota (1000) and FREE plan
 *   <li>Save to database
 * </ol>
 *
 * <h2>Thread Safety:</h2>
 *
 * <p>This service is stateless and thread-safe. Uses Spring's singleton scope.
 *
 * @author Notification Hub Team
 * @version 1.0
 * @since 1.0
 * @see TenantRepository
 * @see ApiKeyRepository
 * @see JwtUtils
 * @see org.springframework.security.authentication.AuthenticationManager
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;

    private final Integer QUOTA_LIMIT_DEFAULT = 1000;

    /**
     * Authenticate user and generate JWT token
     *
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
     *
     * @param rawApiKey the raw API key to validate
     * @return a set of permission names associated with the valid API key
     */
    @Cacheable(value = "apiKeyPermissions", key = "#rawApiKey")
    public ApiKeyValidationResponse validateApiKey(String rawApiKey) {
        ApiKey apiKey =
                apiKeyRepository
                        .findByKey(rawApiKey)
                        .orElseThrow(() -> new BaseException(ApiErrorMessage.INVALID_API_KEY));

        if (apiKey.isRevoked()) {
            throw new BaseException(ApiErrorMessage.API_KEY_REVOKED);
        }

        Set<String> permissions =
                apiKey.getPermissions().stream()
                        .filter(p -> p.getType() == PermissionType.API)
                        .map(Permission::getName)
                        .collect(Collectors.toSet());

        String tenantId = apiKey.getTenant() != null ? apiKey.getTenant().getId() : null;
        return new ApiKeyValidationResponse(tenantId, permissions);
    }

    /**
     * Register a new tenant
     *
     * @param tenantCreateRequest the tenant creation request
     * @return the created tenant
     */
    public Tenant registerNewTenant(TenantCreateRequest tenantCreateRequest) {
        if (tenantRepository.findByEmail(tenantCreateRequest.getEmail()).isPresent()) {
            throw new BaseException(ApiErrorMessage.EMAIL_NOT_AVAILABLE);
        }

        Tenant tenant =
                Tenant.builder()
                        .name(tenantCreateRequest.getName())
                        .email(tenantCreateRequest.getEmail())
                        .quotaLimit(QUOTA_LIMIT_DEFAULT)
                        .quotaUsed(0)
                        .plan(Plan.FREE)
                        .password(passwordEncoder.encode(tenantCreateRequest.getPassword()))
                        .permissions(new HashSet<>())
                        .build();

        return tenantRepository.save(tenant);
    }

    /**
     * Get the currently authenticated user
     *
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
