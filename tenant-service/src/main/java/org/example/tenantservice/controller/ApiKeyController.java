package org.example.tenantservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.tenantservice.common.baseclass.ApiResponse;
import org.example.tenantservice.dto.request.ApiKeyCreateRequest;
import org.example.tenantservice.dto.response.ApiKeyResponse;
import org.example.tenantservice.mapper.ApiKeyMapper;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * Create a new API key
     * @param request the API key creation request
     * @return the created API key response
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyResponse>> createApiKey(@Valid @RequestBody ApiKeyCreateRequest request) {
        ApiKey savedApiKey = apiKeyService.createApiKey(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ApiKeyMapper.toDto(savedApiKey)));
    }

    /**
     * Revoke an existing API key
     * @param apiKeyId the ID of the API key to revoke
     * @return success message
     */
    @PutMapping("/{apiKeyId}/revoke")
    public ResponseEntity<ApiResponse<String>> revokeApiKey(@PathVariable String apiKeyId) {
        apiKeyService.revokeApiKey(apiKeyId);
        return ResponseEntity.ok(ApiResponse.ok("API key revoked successfully"));
    }

    /**
     * List all API keys for the current tenant
     * @return set of API key responses
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Set<ApiKeyResponse>>> listApiKeys() {
        Set<ApiKey> apiKeys = apiKeyService.listApiKeys();
        Set<ApiKeyResponse> apiKeyResponses = apiKeys.stream().map(ApiKeyMapper::toDto).collect(Collectors.toSet());
        return ResponseEntity.ok(ApiResponse.ok(apiKeyResponses));
    }

    /**
     * Get details of a specific API key
     * @param apiKeyId the ID of the API key
     * @return the API key response
     */
    @GetMapping("/{apiKeyId}")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> getApiKeyDetails(@PathVariable String apiKeyId) {
        ApiKey apiKey = apiKeyService.getApiKeyDetails(apiKeyId);
        return ResponseEntity.ok(ApiResponse.ok(ApiKeyMapper.toDto(apiKey)));
    }

    /**
     * Update an existing API key
     * @param apiKeyId the ID of the API key to update
     * @param request the API key update request
     * @return the updated API key response
     */
    @PatchMapping("/{apiKeyId}")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> updateApiKey(@PathVariable String apiKeyId, @RequestBody ApiKeyCreateRequest request) {
        ApiKey updatedApiKey = apiKeyService.updateApiKey(apiKeyId, request);
        return ResponseEntity.ok(ApiResponse.ok(ApiKeyMapper.toDto(updatedApiKey)));
    }
}
