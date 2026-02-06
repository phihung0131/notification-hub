package org.example.tenantservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.common.enums.TenantStatus;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.TenantRepository;
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
@DisplayName("QuotaService Unit Tests")
class QuotaServiceTest {

    @Mock private TenantRepository tenantRepository;

    @Mock private CacheService cacheService;

    @InjectMocks private QuotaService quotaService;

    private Tenant testTenant;
    private String testTenantId;

    @BeforeEach
    void setUp() {
        testTenantId = "test-tenant-id";
        testTenant =
                Tenant.builder()
                        .id(testTenantId)
                        .name("Test Tenant")
                        .email("test@example.com")
                        .plan(Plan.FREE)
                        .status(TenantStatus.ACTIVE)
                        .quotaLimit(100)
                        .quotaUsed(50)
                        .build();
    }

    @Test
    @DisplayName("Should return true when quota is available (from cache)")
    void hasAvailableQuota_CacheHit_ReturnsTrue() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.quotaUsage(testTenantId)))
                .thenReturn(Optional.of(50));
        when(cacheService.get(CacheKeyGenerator.quotaLimit(testTenantId)))
                .thenReturn(Optional.of(100));

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result);
        verify(cacheService).get(CacheKeyGenerator.quotaUsage(testTenantId));
        verify(cacheService).get(CacheKeyGenerator.quotaLimit(testTenantId));
        verify(tenantRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("Should return false when quota is exceeded")
    void hasAvailableQuota_ExceededQuota_ReturnsFalse() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.quotaUsage(testTenantId)))
                .thenReturn(Optional.of(100));
        when(cacheService.get(CacheKeyGenerator.quotaLimit(testTenantId)))
                .thenReturn(Optional.of(100));

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return true for unlimited quota (-1)")
    void hasAvailableQuota_UnlimitedQuota_ReturnsTrue() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.quotaUsage(testTenantId)))
                .thenReturn(Optional.of(1000));
        when(cacheService.get(CacheKeyGenerator.quotaLimit(testTenantId)))
                .thenReturn(Optional.of(-1));

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("Should load from database when cache misses")
    void hasAvailableQuota_CacheMiss_LoadsFromDatabase() {
        // Arrange
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(tenantRepository.findById(testTenantId)).thenReturn(Optional.of(testTenant));

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result);
        verify(tenantRepository).findById(testTenantId);
        verify(cacheService, times(2)).set(anyString(), anyInt());
    }

    @Test
    @DisplayName("Should return false when tenant not found")
    void hasAvailableQuota_TenantNotFound_ReturnsFalse() {
        // Arrange
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(tenantRepository.findById(testTenantId)).thenReturn(Optional.empty());

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return true on error (fail-open)")
    void hasAvailableQuota_Error_ReturnsTrue() {
        // Arrange
        when(cacheService.get(anyString())).thenThrow(new RuntimeException("Redis error"));

        // Act
        boolean result = quotaService.hasAvailableQuota(testTenantId);

        // Assert
        assertTrue(result); // Fail-open to avoid blocking legitimate users
    }

    @Test
    @DisplayName("Should return correct remaining quota")
    void getRemainingQuota_Success() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.quotaUsage(testTenantId)))
                .thenReturn(Optional.of(30));
        when(cacheService.get(CacheKeyGenerator.quotaLimit(testTenantId)))
                .thenReturn(Optional.of(100));

        // Act
        int remaining = quotaService.getRemainingQuota(testTenantId);

        // Assert
        assertEquals(70, remaining);
    }

    @Test
    @DisplayName("Should return -1 for unlimited quota")
    void getRemainingQuota_UnlimitedQuota_ReturnsMinusOne() {
        // Arrange
        when(cacheService.get(CacheKeyGenerator.quotaUsage(testTenantId)))
                .thenReturn(Optional.of(1000));
        when(cacheService.get(CacheKeyGenerator.quotaLimit(testTenantId)))
                .thenReturn(Optional.of(-1));

        // Act
        int remaining = quotaService.getRemainingQuota(testTenantId);

        // Assert
        assertEquals(-1, remaining);
    }

    @Test
    @DisplayName("Should increment quota and sync to cache")
    void incrementQuotaUsed_Success() {
        // Arrange
        testTenant.setQuotaUsed(60); // After increment
        when(tenantRepository.findById(testTenantId)).thenReturn(Optional.of(testTenant));

        // Act
        quotaService.incrementQuotaUsed(testTenantId, 10);

        // Assert
        verify(tenantRepository).incrementQuotaUsed(testTenantId, 10);
        verify(tenantRepository).findById(testTenantId);
        verify(cacheService).set(CacheKeyGenerator.quotaUsage(testTenantId), 60);
    }

    @Test
    @DisplayName("Should sync quota to cache")
    void syncQuotaToCache_Success() {
        // Act
        quotaService.syncQuotaToCache(testTenant);

        // Assert
        verify(cacheService)
                .set(CacheKeyGenerator.quotaUsage(testTenantId), testTenant.getQuotaUsed());
        verify(cacheService)
                .set(CacheKeyGenerator.quotaLimit(testTenantId), testTenant.getQuotaLimit());
    }

    @Test
    @DisplayName("Should clear quota cache")
    void clearQuotaCache_Success() {
        // Act
        quotaService.clearQuotaCache(testTenantId);

        // Assert
        verify(cacheService).delete(CacheKeyGenerator.quotaUsage(testTenantId));
        verify(cacheService).delete(CacheKeyGenerator.quotaLimit(testTenantId));
    }
}
