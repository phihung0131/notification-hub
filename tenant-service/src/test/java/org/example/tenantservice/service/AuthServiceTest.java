package org.example.tenantservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.example.commons.exception.BaseException;
import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.config.security.CustomUserDetails;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.dto.response.ApiKeyValidationResponse;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthService}. Tests authentication, registration, and API key validation.
 *
 * @author Notification Hub Team
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private TenantRepository tenantRepository;

    @Mock private ApiKeyRepository apiKeyRepository;

    @Mock private PasswordEncoder passwordEncoder;

    @Mock private AuthenticationManager authenticationManager;

    @Mock private JwtUtils jwtUtils;

    @Mock private SecurityContext securityContext;

    @Mock private Authentication authentication;

    @InjectMocks private AuthService authService;

    private Tenant testTenant;
    private TenantCreateRequest createRequest;

    @BeforeEach
    void setUp() {
        testTenant =
                Tenant.builder()
                        .id("tenant-123")
                        .name("Test Tenant")
                        .email("test@example.com")
                        .password("encoded-password")
                        .quotaLimit(1000)
                        .quotaUsed(0)
                        .plan(Plan.FREE)
                        .permissions(new HashSet<>())
                        .build();

        createRequest = new TenantCreateRequest();
        createRequest.setName("New Tenant");
        createRequest.setEmail("new@example.com");
        createRequest.setPassword("password123");
    }

    @Test
    @DisplayName("Should successfully authenticate user and generate JWT token")
    void loginUser_ValidCredentials_ReturnsJwtToken() {
        // Given
        String email = "test@example.com";
        String password = "password123";
        String expectedToken = "jwt-token-xyz";

        //        UsernamePasswordAuthenticationToken authToken =
        //                new UsernamePasswordAuthenticationToken(email, password);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(jwtUtils.generateJwtToken(authentication)).thenReturn(expectedToken);

        // When
        String actualToken = authService.loginUser(email, password);

        // Then
        assertEquals(expectedToken, actualToken);
        verify(authenticationManager)
                .authenticate(
                        argThat(
                                token ->
                                        token.getPrincipal().equals(email)
                                                && token.getCredentials().equals(password)));
        verify(jwtUtils).generateJwtToken(authentication);

        // Verify SecurityContext was updated
        //        ArgumentCaptor<Authentication> authCaptor =
        // ArgumentCaptor.forClass(Authentication.class);
        verify(authentication, never())
                .setAuthenticated(anyBoolean()); // SecurityContextHolder handles this
    }

    @Test
    @DisplayName("Should throw exception when authentication fails with invalid credentials")
    void loginUser_InvalidCredentials_ThrowsException() {
        // Given
        String email = "test@example.com";
        String password = "wrong-password";

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        // When & Then
        assertThrows(BadCredentialsException.class, () -> authService.loginUser(email, password));

        verify(authenticationManager).authenticate(any());
        verify(jwtUtils, never()).generateJwtToken(any());
    }

    @Test
    @DisplayName("Should successfully register new tenant with encoded password")
    void registerNewTenant_NewEmail_CreatesTenant() {
        // Given
        String encodedPassword = "encoded-password-hash";

        when(tenantRepository.findByEmail(createRequest.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(createRequest.getPassword())).thenReturn(encodedPassword);
        when(tenantRepository.save(any(Tenant.class)))
                .thenAnswer(
                        invocation -> {
                            Tenant saved = invocation.getArgument(0);
                            saved.setId("new-tenant-id");
                            return saved;
                        });

        // When
        Tenant result = authService.registerNewTenant(createRequest);

        // Then
        assertNotNull(result);
        assertEquals("New Tenant", result.getName());
        assertEquals("new@example.com", result.getEmail());
        assertEquals(encodedPassword, result.getPassword());
        assertEquals(1000, result.getQuotaLimit());
        assertEquals(0, result.getQuotaUsed());
        assertEquals(Plan.FREE, result.getPlan());
        assertNotNull(result.getPermissions());
        assertTrue(result.getPermissions().isEmpty());

        verify(tenantRepository).findByEmail("new@example.com");
        verify(passwordEncoder).encode("password123");
        verify(tenantRepository)
                .save(
                        argThat(
                                tenant ->
                                        tenant.getName().equals("New Tenant")
                                                && tenant.getEmail().equals("new@example.com")
                                                && tenant.getPassword().equals(encodedPassword)));
    }

    @Test
    @DisplayName("Should throw exception when registering with existing email")
    void registerNewTenant_ExistingEmail_ThrowsException() {
        // Given
        when(tenantRepository.findByEmail(createRequest.getEmail()))
                .thenReturn(Optional.of(testTenant));

        // When & Then
        BaseException exception =
                assertThrows(
                        BaseException.class, () -> authService.registerNewTenant(createRequest));

        assertEquals(1000001, exception.getCode()); // EMAIL_NOT_AVAILABLE
        verify(tenantRepository).findByEmail("new@example.com");
        verify(passwordEncoder, never()).encode(anyString());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should validate API key and return tenant info with permissions")
    void validateApiKey_ValidKey_ReturnsValidationResponse() {
        // Given
        String rawApiKey = "sk_test_valid_key";
        String tenantId = "tenant-123";

        Permission perm1 = new Permission();
        perm1.setName("SEND_NOTIFICATION");
        perm1.setType(PermissionType.API);

        Permission perm2 = new Permission();
        perm2.setName("VIEW_ANALYTICS");
        perm2.setType(PermissionType.API);

        Permission uiPerm = new Permission();
        uiPerm.setName("UI_ACCESS");
        uiPerm.setType(PermissionType.UI); // Should be filtered out

        Set<Permission> permissions = Set.of(perm1, perm2, uiPerm);

        ApiKey apiKey = new ApiKey();
        apiKey.setKey(rawApiKey);
        apiKey.setRevoked(false);
        apiKey.setTenant(testTenant);
        apiKey.setPermissions(permissions);

        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(apiKey));

        // When
        ApiKeyValidationResponse response = authService.validateApiKey(rawApiKey);

        // Then
        assertNotNull(response);
        assertEquals(tenantId, response.tenantId());
        assertEquals(2, response.permissions().size());
        assertTrue(response.permissions().contains("SEND_NOTIFICATION"));
        assertTrue(response.permissions().contains("VIEW_ANALYTICS"));
        assertFalse(response.permissions().contains("UI_ACCESS")); // Filtered out

        verify(apiKeyRepository).findByKey(rawApiKey);
    }

    @Test
    @DisplayName("Should throw exception when API key is not found")
    void validateApiKey_KeyNotFound_ThrowsException() {
        // Given
        String rawApiKey = "sk_test_invalid_key";

        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.empty());

        // When & Then
        BaseException exception =
                assertThrows(BaseException.class, () -> authService.validateApiKey(rawApiKey));

        assertEquals(1000002, exception.getCode()); // INVALID_API_KEY
        verify(apiKeyRepository).findByKey(rawApiKey);
    }

    @Test
    @DisplayName("Should throw exception when API key is revoked")
    void validateApiKey_RevokedKey_ThrowsException() {
        // Given
        String rawApiKey = "sk_test_revoked_key";

        ApiKey apiKey = new ApiKey();
        apiKey.setKey(rawApiKey);
        apiKey.setRevoked(true);
        apiKey.setTenant(testTenant);

        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(apiKey));

        // When & Then
        BaseException exception =
                assertThrows(BaseException.class, () -> authService.validateApiKey(rawApiKey));

        assertEquals(1000009, exception.getCode()); // API_KEY_REVOKED
        verify(apiKeyRepository).findByKey(rawApiKey);
    }

    @Test
    @DisplayName(
            "Should return validation response with empty permissions when API key has no API permissions")
    void validateApiKey_NoApiPermissions_ReturnsEmptyPermissions() {
        // Given
        String rawApiKey = "sk_test_no_perms";

        Permission uiPerm = new Permission();
        uiPerm.setName("UI_ACCESS");
        uiPerm.setType(PermissionType.UI);

        ApiKey apiKey = new ApiKey();
        apiKey.setKey(rawApiKey);
        apiKey.setRevoked(false);
        apiKey.setTenant(testTenant);
        apiKey.setPermissions(Set.of(uiPerm));

        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(apiKey));

        // When
        ApiKeyValidationResponse response = authService.validateApiKey(rawApiKey);

        // Then
        assertNotNull(response);
        assertTrue(response.permissions().isEmpty());
    }

    @Test
    @DisplayName("Should return current authenticated user")
    void getCurrentUser_Authenticated_ReturnsUserDetails() {
        // Given
        CustomUserDetails userDetails =
                new CustomUserDetails(
                        testTenant.getId(),
                        testTenant.getEmail(),
                        testTenant.getPassword(),
                        new HashSet<>());

        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(userDetails);

        // When
        CustomUserDetails result = authService.getCurrentUser();

        // Then
        assertNotNull(result);
        assertEquals(userDetails, result);
        assertEquals(testTenant.getId(), result.getId());
        assertEquals(testTenant.getEmail(), result.getEmail());

        verify(securityContext).getAuthentication();
        verify(authentication).isAuthenticated();
        verify(authentication).getPrincipal();
    }

    @Test
    @DisplayName("Should throw exception when no authentication present")
    void getCurrentUser_NoAuthentication_ThrowsException() {
        // Given
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(null);

        // When & Then
        BaseException exception =
                assertThrows(BaseException.class, () -> authService.getCurrentUser());

        assertEquals(1000006, exception.getCode()); // UNAUTHORIZED
    }

    @Test
    @DisplayName("Should throw exception when authentication is not authenticated")
    void getCurrentUser_NotAuthenticated_ThrowsException() {
        // Given
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);

        // When & Then
        BaseException exception =
                assertThrows(BaseException.class, () -> authService.getCurrentUser());

        assertEquals(1000006, exception.getCode()); // UNAUTHORIZED
    }

    @Test
    @DisplayName("Should throw exception when principal is not CustomUserDetails")
    void getCurrentUser_InvalidPrincipal_ThrowsException() {
        // Given
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("not-a-user-details");

        // When & Then
        BaseException exception =
                assertThrows(BaseException.class, () -> authService.getCurrentUser());

        assertEquals(1000006, exception.getCode()); // UNAUTHORIZED
    }

    @Test
    @DisplayName("Should handle API key with null tenant gracefully")
    void validateApiKey_NullTenant_ReturnsNullTenantId() {
        // Given
        String rawApiKey = "sk_test_no_tenant";

        ApiKey apiKey = new ApiKey();
        apiKey.setKey(rawApiKey);
        apiKey.setRevoked(false);
        apiKey.setTenant(null); // No tenant associated
        apiKey.setPermissions(new HashSet<>());

        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(apiKey));

        // When
        ApiKeyValidationResponse response = authService.validateApiKey(rawApiKey);

        // Then
        assertNotNull(response);
        assertNull(response.tenantId());
        assertTrue(response.permissions().isEmpty());
    }
}
