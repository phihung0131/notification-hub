package org.example.gatewayservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.example.gatewayservice.model.ApiKeyValidationResponse;
import org.example.proto.tenant.TenantServiceGrpc;
import org.example.proto.tenant.ValidateApiKeyRequest;
import org.example.proto.tenant.ValidateApiKeyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.grpc.StatusRuntimeException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantValidationClient Unit Tests")
class TenantValidationClientTest {

    @Mock private TenantServiceGrpc.TenantServiceBlockingStub tenantServiceStub;

    @InjectMocks private TenantValidationClient validationClient;

    private String testApiKey;

    @BeforeEach
    void setUp() {
        testApiKey = "test-api-key-12345678";
        // Inject the mock stub via reflection
        ReflectionTestUtils.setField(validationClient, "tenantServiceStub", tenantServiceStub);
    }

    @Test
    @DisplayName("Should validate API key successfully via gRPC")
    void validateApiKey_ValidKey_ReturnsResponse() {
        // Arrange
        ValidateApiKeyResponse grpcResponse =
                ValidateApiKeyResponse.newBuilder()
                        .setIsValid(true)
                        .setTenantId("tenant-123")
                        .setStatus("ACTIVE")
                        .setPlan("PRO")
                        .build();

        when(tenantServiceStub.validateApiKey(any(ValidateApiKeyRequest.class)))
                .thenReturn(grpcResponse);

        // Act
        Mono<ApiKeyValidationResponse> result = validationClient.validateApiKey(testApiKey);

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(response -> response.tenantId().equals("tenant-123"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error for invalid API key")
    void validateApiKey_InvalidKey_ThrowsError() {
        // Arrange
        ValidateApiKeyResponse grpcResponse =
                ValidateApiKeyResponse.newBuilder()
                        .setIsValid(false)
                        .setTenantId("")
                        .setStatus("")
                        .setPlan("")
                        .build();

        when(tenantServiceStub.validateApiKey(any(ValidateApiKeyRequest.class)))
                .thenReturn(grpcResponse);

        // Act
        Mono<ApiKeyValidationResponse> result = validationClient.validateApiKey(testApiKey);

        // Assert
        StepVerifier.create(result).expectError(RuntimeException.class).verify();
    }

    @Test
    @DisplayName("Should handle gRPC error gracefully")
    void validateApiKey_GrpcError_ThrowsError() {
        // Arrange
        when(tenantServiceStub.validateApiKey(any(ValidateApiKeyRequest.class)))
                .thenThrow(StatusRuntimeException.class);

        // Act
        Mono<ApiKeyValidationResponse> result = validationClient.validateApiKey(testApiKey);

        // Assert
        StepVerifier.create(result).expectError(RuntimeException.class).verify();
    }
}
