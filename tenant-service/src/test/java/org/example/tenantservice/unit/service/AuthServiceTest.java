package org.example.tenantservice.unit.service;

import org.example.tenantservice.common.exception.ApiErrorMessage;
import org.example.tenantservice.common.exception.BaseException;
import org.example.tenantservice.config.security.CustomUserDetails;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.example.tenantservice.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    TenantRepository tenantRepository;

    @Mock
    ApiKeyRepository apiKeyRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    JwtUtils jwtUtils;

    @InjectMocks
    AuthService authService;

    @Captor
    ArgumentCaptor<Tenant> tenantCaptor;

    @BeforeEach
    void setUp() {
        // ensure clean security context for each test
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerNewTenant_whenEmailTaken_thenThrow() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setEmail("taken@example.com");
        req.setName("Name");
        req.setPassword("pass");

        when(tenantRepository.findByEmail(req.getEmail())).thenReturn(Optional.of(new Tenant()));

        BaseException ex = assertThrows(BaseException.class, () -> authService.registerNewTenant(req));
        assertEquals(ApiErrorMessage.EMAIL_NOT_AVAILABLE.getCode(), ex.getCode());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void registerNewTenant_whenOk_thenSaveAndReturn() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setEmail("new@example.com");
        req.setName("New Tenant");
        req.setPassword("secret");

        when(tenantRepository.findByEmail(req.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(req.getPassword())).thenReturn("encoded-pass");

        Tenant saved = Tenant.builder()
                .id("id-1")
                .email(req.getEmail())
                .name(req.getName())
                .password("encoded-pass")
                .plan(Plan.FREE)
                .quotaLimit(1000)
                .quotaUsed(0)
                .permissions(new HashSet<>())
                .build();

        when(tenantRepository.save(any())).thenReturn(saved);

        Tenant result = authService.registerNewTenant(req);

        assertNotNull(result);
        assertEquals(saved.getId(), result.getId());

        verify(tenantRepository).save(tenantCaptor.capture());
        Tenant toSave = tenantCaptor.getValue();
        assertEquals(req.getEmail(), toSave.getEmail());
        assertEquals("encoded-pass", toSave.getPassword());
        assertEquals(req.getName(), toSave.getName());
    }

    @Test
    void validateApiKeyAndGetPermissions_whenValid_thenReturnPermissions() {
        String raw = "raw-key";

        Permission pApi = new Permission();
        pApi.setName("READ");
        pApi.setType(PermissionType.API);

        ApiKey apiKey = new ApiKey();
        apiKey.setRevoked(false);
        apiKey.setKey("raw-key");
        apiKey.setPermissions(Set.of(pApi));

        when(apiKeyRepository.findByKey(raw)).thenReturn(Optional.of(apiKey));

        Set<String> perms = authService.validateApiKeyAndGetPermissions(raw);

        assertNotNull(perms);
        assertTrue(perms.contains("READ"));
    }

    @Test
    void validateApiKeyAndGetPermissions_whenInvalid_thenThrow() {
        when(apiKeyRepository.findByKey(any())).thenReturn(Optional.empty());

        BaseException ex = assertThrows(BaseException.class, () -> authService.validateApiKeyAndGetPermissions("nope"));
        assertEquals(ApiErrorMessage.INVALID_API_KEY.getCode(), ex.getCode());
    }

    @Test
    void getCurrentUser_whenAuthenticated_thenReturnUserDetails() {
        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(userDetails);

        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        CustomUserDetails result = authService.getCurrentUser();
        assertSame(userDetails, result);
    }

    @Test
    void getCurrentUser_whenNotAuthenticated_thenThrow() {
        SecurityContextHolder.clearContext();
        BaseException ex = assertThrows(BaseException.class, () -> authService.getCurrentUser());
        assertEquals(ApiErrorMessage.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void loginUser_whenSuccessful_thenReturnTokenAndSetSecurityContext() {
        String email = "a@b.com";
        String password = "pass";

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(jwtUtils.generateJwtToken(auth)).thenReturn("jwt-token");

        String token = authService.loginUser(email, password);
        assertEquals("jwt-token", token);

        Authentication stored = SecurityContextHolder.getContext().getAuthentication();
        assertSame(auth, stored);
    }

}


