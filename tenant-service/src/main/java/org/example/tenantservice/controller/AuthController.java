package org.example.tenantservice.controller;

import jakarta.validation.Valid;

import org.example.commons.baseclass.ApiResponse;
import org.example.tenantservice.dto.request.LoginRequest;
import org.example.tenantservice.dto.request.TenantCreateRequest;
import org.example.tenantservice.dto.response.ApiKeyValidationResponse;
import org.example.tenantservice.dto.response.JwtResponse;
import org.example.tenantservice.dto.response.TenantResponse;
import org.example.tenantservice.mapper.TenantMapper;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    /**
     * Public endpoint for user registration.
     *
     * @param tenantCreateRequest User registration request
     * @return Created Tenant details
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TenantResponse>> registerUser(
            @Valid @RequestBody TenantCreateRequest tenantCreateRequest) {
        Tenant newTenant = authService.registerNewTenant(tenantCreateRequest);
        TenantResponse response = TenantMapper.toDto(newTenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * Public endpoint for user login.
     *
     * @param loginRequest User login request
     * @return JWT token if login is successful
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtResponse>> login(
            @Valid @RequestBody LoginRequest loginRequest) {
        String jwt = authService.loginUser(loginRequest.getEmail(), loginRequest.getPassword());
        return ResponseEntity.ok(ApiResponse.ok(new JwtResponse(jwt)));
    }

    // Internal endpoint for API key validation
    // TODO: Convert to gRPC to optimize performance
    @GetMapping("/internal/apikeys/validate")
    public ResponseEntity<ApiKeyValidationResponse> validateApiKey(
            @RequestHeader("X-API-Key-To-Validate") String rawApiKey) {
        ApiKeyValidationResponse resp = authService.validateApiKey(rawApiKey);
        return ResponseEntity.ok(resp);
    }
}
