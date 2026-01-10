package org.example.notificationservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import org.example.notificationservice.grpc.client.TenantServiceGrpcClient;
import org.example.proto.tenant.CheckQuotaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuotaCheckService Unit Tests")
class QuotaCheckServiceTest {

    @Mock private TenantServiceGrpcClient tenantServiceGrpcClient;

    @Mock private RedisTemplate<String, Object> redisTemplate;

    @Mock private ValueOperations<String, Object> valueOperations;

    @InjectMocks private QuotaCheckService quotaCheckService;

    private String testTenantId;

    @BeforeEach
    void setUp() {
        testTenantId = "test-tenant-123";
    }

    @Test
    @DisplayName("Should return true when quota available (cache hit)")
    void hasAvailableQuota_CacheHit_ReturnsTrue() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(true);

        // Act
        boolean result = quotaCheckService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result);
        verify(valueOperations).get(anyString());
        verify(tenantServiceGrpcClient, never()).checkQuota(anyString());
    }

    @Test
    @DisplayName("Should call gRPC when cache misses")
    void hasAvailableQuota_CacheMiss_CallsGrpc() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        CheckQuotaResponse grpcResponse =
                CheckQuotaResponse.newBuilder()
                        .setIsAllowed(true)
                        .setRemaining(50)
                        .setLimit(100)
                        .build();
        when(tenantServiceGrpcClient.checkQuota(testTenantId)).thenReturn(grpcResponse);

        // Act
        boolean result = quotaCheckService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result);
        verify(tenantServiceGrpcClient).checkQuota(testTenantId);
        verify(valueOperations).set(anyString(), eq(true), any(Duration.class));
    }

    @Test
    @DisplayName("Should return false when quota exceeded")
    void hasAvailableQuota_QuotaExceeded_ReturnsFalse() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        CheckQuotaResponse grpcResponse =
                CheckQuotaResponse.newBuilder()
                        .setIsAllowed(false)
                        .setRemaining(0)
                        .setLimit(100)
                        .build();
        when(tenantServiceGrpcClient.checkQuota(testTenantId)).thenReturn(grpcResponse);

        // Act
        boolean result = quotaCheckService.hasAvailableQuota(testTenantId);

        // Assert
        assertFalse(result);
        verify(valueOperations).set(anyString(), eq(false), any(Duration.class));
    }

    @Test
    @DisplayName("Should fail-open on gRPC error")
    void hasAvailableQuota_GrpcError_FailsOpen() {
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(tenantServiceGrpcClient.checkQuota(testTenantId))
                .thenThrow(StatusRuntimeException.class);

        // Act
        boolean result = quotaCheckService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result); // Fail-open to avoid blocking legitimate users
    }

    @Test
    @DisplayName("Should get remaining quota successfully")
    void getRemainingQuota_Success() {
        // Arrange
        CheckQuotaResponse grpcResponse =
                CheckQuotaResponse.newBuilder()
                        .setIsAllowed(true)
                        .setRemaining(75)
                        .setLimit(100)
                        .build();
        when(tenantServiceGrpcClient.checkQuota(testTenantId)).thenReturn(grpcResponse);

        // Act
        int remaining = quotaCheckService.getRemainingQuota(testTenantId);

        // Assert
        assertEquals(75, remaining);
    }

    @Test
    @DisplayName("Should return 0 on error when getting remaining quota")
    void getRemainingQuota_Error_ReturnsZero() {
        // Arrange
        when(tenantServiceGrpcClient.checkQuota(testTenantId))
                .thenThrow(new RuntimeException("Test error"));

        // Act
        int remaining = quotaCheckService.getRemainingQuota(testTenantId);

        // Assert
        assertEquals(0, remaining);
    }

    @Test
    @DisplayName("Should invalidate cache successfully")
    void invalidateQuotaCache_Success() {
        // Act
        quotaCheckService.invalidateQuotaCache(testTenantId);

        // Assert
        verify(redisTemplate).delete(anyString());
    }
}
