package org.example.tenantservice.slide.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tenantservice.controller.AuthController;
import org.example.tenantservice.config.security.JwtAuthFilter;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.config.security.UserDetailsServiceImpl;
import org.example.tenantservice.dto.request.LoginRequest;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = AuthController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AuthService authService;

    // mock security-related beans so JwtAuthFilter and other security components don't block context startup
    @MockitoBean
    JwtUtils jwtUtils;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    JwtAuthFilter jwtAuthFilter;

    @Test
    @DisplayName("POST /auth/register - returns 201 and tenant payload")
    void registerUser_returnsCreated() throws Exception {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setEmail("new@example.com");
        req.setName("New");
        req.setPassword("secret");

        Tenant saved = Tenant.builder()
                .id("t-1")
                .email(req.getEmail())
                .name(req.getName())
                .build();

        when(authService.registerNewTenant(any())).thenReturn(saved);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email", is(saved.getEmail())))
                .andExpect(jsonPath("$.data.id", is(saved.getId())));
    }

    @Test
    @DisplayName("POST /auth/login - returns 200 and jwt token")
    void login_returnsToken() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("a@b.com");
        req.setPassword("pass");

        when(authService.loginUser(eq(req.getEmail()), eq(req.getPassword()))).thenReturn("jwt-token");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", is("jwt-token")));
    }

    @Test
    @DisplayName("POST /auth/login - wrong credentials returns null token")
    void login_wrongCredentials_returnsNullToken() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("nope@example.com");
        req.setPassword("bad");

        when(authService.loginUser(eq(req.getEmail()), eq(req.getPassword()))).thenReturn(null);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", nullValue()));
    }

    @Test
    @DisplayName("GET /auth/internal/apikeys/validate - returns permissions set")
    void validateApiKey_returnsPermissions() throws Exception {
        when(authService.validateApiKeyAndGetPermissions("raw-key")).thenReturn(java.util.Set.of("READ"));

        mockMvc.perform(get("/auth/internal/apikeys/validate")
                        .header("X-API-Key-To-Validate", "raw-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("[0]", is("READ")));
    }
}
