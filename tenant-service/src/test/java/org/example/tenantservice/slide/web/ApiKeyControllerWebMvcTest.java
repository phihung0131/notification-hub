package org.example.tenantservice.slide.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tenantservice.controller.ApiKeyController;
import org.example.tenantservice.config.security.JwtAuthFilter;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.config.security.UserDetailsServiceImpl;
import org.example.tenantservice.dto.request.ApiKeyCreateRequest;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.service.ApiKeyService;
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

import java.time.Instant;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = ApiKeyController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class ApiKeyControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ApiKeyService apiKeyService;

    // mock security beans in case they are scanned
    @MockitoBean
    JwtUtils jwtUtils;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    JwtAuthFilter jwtAuthFilter;

    @Test
    @DisplayName("POST /api-keys - creates api key and returns 201")
    void createApiKey_returnsCreated() throws Exception {
        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now().plusSeconds(3600));
        req.setPermissionIds(Set.of("p1"));

        Permission perm = Permission.builder().id("p1").name("notification:send").type(org.example.tenantservice.common.enums.PermissionType.API).build();
        ApiKey saved = ApiKey.builder()
                .id("k-1")
                .key("raw-key")
                .expiredAt(req.getExpiredAt())
                .revoked(false)
                .permissions(Set.of(perm))
                .build();

        when(apiKeyService.createApiKey(any())).thenReturn(saved);

        mockMvc.perform(post("/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", is("k-1")))
                .andExpect(jsonPath("$.data.key", is("raw-key")));
    }

    @Test
    @DisplayName("GET /api-keys - returns list of api keys")
    void listApiKeys_returnsList() throws Exception {
        Permission perm = Permission.builder().id("p1").name("notification:send").type(org.example.tenantservice.common.enums.PermissionType.API).build();
        ApiKey k = ApiKey.builder().id("k-2").key("k2").permissions(Set.of(perm)).build();

        when(apiKeyService.listApiKeys()).thenReturn(Set.of(k));

        mockMvc.perform(get("/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", is("k-2")));
    }

    @Test
    @DisplayName("PUT /api-keys/{id}/revoke - revokes and returns ok")
    void revokeApiKey_returnsOk() throws Exception {
        doNothing().when(apiKeyService).revokeApiKey(eq("k-1"));

        mockMvc.perform(put("/api-keys/k-1/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", is("API key revoked successfully")));
    }

    @Test
    @DisplayName("GET /api-keys/{id} - returns details")
    void getApiKeyDetails_returnsDetails() throws Exception {
        Permission perm = Permission.builder().id("p1").name("notification:send").type(org.example.tenantservice.common.enums.PermissionType.API).build();
        ApiKey k = ApiKey.builder().id("k-3").key("k3").permissions(Set.of(perm)).build();

        when(apiKeyService.getApiKeyDetails(eq("k-3"))).thenReturn(k);

        mockMvc.perform(get("/api-keys/k-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is("k-3")));
    }

    @Test
    @DisplayName("PATCH /api-keys/{id} - updates and returns updated key")
    void updateApiKey_returnsUpdated() throws Exception {
        ApiKeyCreateRequest req = new ApiKeyCreateRequest();
        req.setExpiredAt(Instant.now().plusSeconds(7200));
        req.setPermissionIds(Set.of("p1"));

        Permission perm = Permission.builder().id("p1").name("notification:send").type(org.example.tenantservice.common.enums.PermissionType.API).build();
        ApiKey updated = ApiKey.builder().id("k-4").key("k4").permissions(Set.of(perm)).expiredAt(req.getExpiredAt()).build();

        when(apiKeyService.updateApiKey(eq("k-4"), any())).thenReturn(updated);

        mockMvc.perform(patch("/api-keys/k-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is("k-4")));
    }
}

