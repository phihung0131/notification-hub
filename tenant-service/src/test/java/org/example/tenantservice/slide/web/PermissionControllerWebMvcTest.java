package org.example.tenantservice.slide.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tenantservice.controller.PermissionController;
import org.example.tenantservice.config.security.JwtAuthFilter;
import org.example.tenantservice.config.security.JwtUtils;
import org.example.tenantservice.config.security.UserDetailsServiceImpl;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.service.PermissionService;
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

import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = PermissionController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class PermissionControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PermissionService permissionService;

    @MockitoBean
    JwtUtils jwtUtils;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    JwtAuthFilter jwtAuthFilter;

    @Test
    @DisplayName("GET /permissions/api - returns permission set")
    void getPermissions_returnsSet() throws Exception {
        Permission p = Permission.builder().id("p1").name("notification:send").type(org.example.tenantservice.common.enums.PermissionType.API).build();
        when(permissionService.getPermissionsByType()).thenReturn(Set.of(p));

        mockMvc.perform(get("/permissions/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name", is("notification:send")));
    }

    @Test
    @DisplayName("POST /permissions - creates permission and returns 201")
    void createPermission_returnsCreated() throws Exception {
        org.example.tenantservice.dto.request.PermissionCreateRequest req = new org.example.tenantservice.dto.request.PermissionCreateRequest();
        req.setName("notif:read");
        req.setType(org.example.tenantservice.common.enums.PermissionType.API);

        Permission saved = Permission.builder().id("p2").name(req.getName()).type(req.getType()).build();
        when(permissionService.createPermission(any())).thenReturn(saved);

        mockMvc.perform(post("/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", is("p2")));
    }
}

