package org.example.tenantservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;

import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.common.enums.TenantStatus;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.service.cache.CacheKeyGenerator;
import org.example.tenantservice.service.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyValidationService Unit Tests")
class ApiKeyValidationServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;

    @Mock private CacheService cacheService;

    @InjectMocks private ApiKeyValidationService apiKeyValidationService;

    private Tenant testTenant;
    private ApiKey testApiKey;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        rawApiKey = "test-api-key-12345678";

        testTenant =
                Tenant.builder()
                        .id("tenant-id")
                        .name("Test Tenant")
                        .email("test@example.com")
                        .plan(Plan.PRO)
                        .status(TenantStatus.ACTIVE)
                        .quotaLimit(1000)
                        .quotaUsed(100)
                        .permissions(new HashSet<>())
                        .build();

        testApiKey =
                ApiKey.builder()
                        .id("key-id")
                        .key(rawApiKey)
                        .tenant(testTenant)
                        .revoked(false)
                        .expiredAt(null)
                        .permissions(new HashSet<>())
                        .build();
    }

    @Test
    @DisplayName("Should return tenant info when API key is valid (cache hit)")
    void validateApiKey_CacheHit_ReturnsTenantInfo() {
        // Arrange
        TenantInfo cachedInfo =
                TenantInfo.builder()
                        .tenantId(testTenant.getId())
                        .status(TenantStatus.ACTIVE.name())
                        .plan(Plan.PRO)
                        .build();

        when(cacheService.get(CacheKeyGenerator.authKey(rawApiKey)))
                .thenReturn(Optional.of(cachedInfo));

        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey(rawApiKey);

        // Assert
        assertNotNull(result);
        assertEquals(testTenant.getId(), result.getTenantId());
        assertEquals(TenantStatus.ACTIVE.name(), result.getStatus());
        verify(apiKeyRepository, never()).findByKey(anyString());
    }

    @Test
    @DisplayName("Should load from database and cache when cache misses")
    void validateApiKey_CacheMiss_LoadsFromDatabase() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.authKey(rawApiKey))).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(testApiKey));

        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey(rawApiKey);

        // Assert
        assertNotNull(result);
        assertEquals(testTenant.getId(), result.getTenantId());
        assertEquals(TenantStatus.ACTIVE.name(), result.getStatus());
        assertEquals(Plan.PRO, result.getPlan());
        verify(apiKeyRepository).findByKey(rawApiKey);
        verify(cacheService)
                .set(
                        eq(CacheKeyGenerator.authKey(rawApiKey)),
                        any(TenantInfo.class),
                        any(Duration.class));
    }

    @Test
    @DisplayName("Should return null for invalid API key")
    void validateApiKey_InvalidKey_ReturnsNull() {
        // Arrange
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.empty());

        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey(rawApiKey);

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for revoked API key")
    void validateApiKey_RevokedKey_ReturnsNull() {
        // Arrange
        testApiKey.setRevoked(true);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(testApiKey));

        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey(rawApiKey);

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for expired API key")
    void validateApiKey_ExpiredKey_ReturnsNull() {
        // Arrange
        testApiKey.setExpiredAt(Instant.now().minusSeconds(3600)); // Expired 1 hour ago
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(testApiKey));

        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey(rawApiKey);

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for empty API key")
    void validateApiKey_EmptyKey_ReturnsNull() {
        // Act
        TenantInfo result = apiKeyValidationService.validateApiKey("");

        // Assert
        assertNull(result);
        verify(cacheService, never()).get(anyString());
    }

    @Test
    @DisplayName("Should return true for valid and active API key")
    void isValidApiKey_ValidAndActive_ReturnsTrue() {
        // Arrange
        TenantInfo cachedInfo =
                TenantInfo.builder()
                        .tenantId(testTenant.getId())
                        .status(TenantStatus.ACTIVE.name())
                        .plan(Plan.PRO)
                        .build();

        when(cacheService.get(anyString())).thenReturn(Optional.of(cachedInfo));

        // Act
        boolean result = apiKeyValidationService.isValidApiKey(rawApiKey);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("Should return false for suspended tenant")
    void isValidApiKey_SuspendedTenant_ReturnsFalse() {
        // Arrange
        testTenant.setStatus(TenantStatus.SUSPENDED);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKey(rawApiKey)).thenReturn(Optional.of(testApiKey));

        // Act
        boolean result = apiKeyValidationService.isValidApiKey(rawApiKey);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("Should invalidate cache")
    void invalidateCache_Success() {
        // Act
        apiKeyValidationService.invalidateCache(rawApiKey);

        // Assert
        verify(cacheService).delete(CacheKeyGenerator.authKey(rawApiKey));
    }
}
