package org.example.tenantservice.grpc.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.example.proto.tenant.*;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.common.enums.TenantStatus;
import org.example.tenantservice.dto.TenantInfo;
import org.example.tenantservice.service.ApiKeyValidationService;
import org.example.tenantservice.service.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * Unit tests for {@link TenantGrpcService}. Tests gRPC endpoints for API key validation and quota
 * checking.
 *
 * @author Notification Hub Team
 */
@ExtendWith(MockitoExtension.class)
class TenantGrpcServiceTest {

    @Mock private ApiKeyValidationService apiKeyValidationService;

    @Mock private QuotaService quotaService;

    @Mock private StreamObserver<ValidateApiKeyResponse> validateResponseObserver;

    @Mock private StreamObserver<CheckQuotaResponse> quotaResponseObserver;

    @InjectMocks private TenantGrpcService grpcService;

    private TenantInfo testTenantInfo;

    @BeforeEach
    void setUp() {
        testTenantInfo =
                TenantInfo.builder()
                        .tenantId("tenant-123")
                        .status(TenantStatus.ACTIVE.name())
                        .plan(Plan.FREE)
                        .build();
    }

    // ===== ValidateApiKey Tests =====

    @Test
    @DisplayName("ValidateApiKey - Should return valid response for valid API key")
    void validateApiKey_ValidKey_ReturnsSuccess() {
        // Given
        String apiKey = "sk_test_valid_key";
        ValidateApiKeyRequest request =
                ValidateApiKeyRequest.newBuilder().setApiKey(apiKey).build();

        when(apiKeyValidationService.validateApiKey(apiKey)).thenReturn(testTenantInfo);

        // When
        grpcService.validateApiKey(request, validateResponseObserver);

        // Then
        ArgumentCaptor<ValidateApiKeyResponse> responseCaptor =
                ArgumentCaptor.forClass(ValidateApiKeyResponse.class);

        verify(validateResponseObserver).onNext(responseCaptor.capture());
        verify(validateResponseObserver).onCompleted();
        verify(validateResponseObserver, never()).onError(any());

        ValidateApiKeyResponse response = responseCaptor.getValue();
        assertTrue(response.getIsValid());
        assertEquals("tenant-123", response.getTenantId());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("FREE", response.getPlan());
    }

    @Test
    @DisplayName("ValidateApiKey - Should return invalid response when API key not found")
    void validateApiKey_InvalidKey_ReturnsInvalidResponse() {
        // Given
        String apiKey = "sk_test_invalid_key";
        ValidateApiKeyRequest request =
                ValidateApiKeyRequest.newBuilder().setApiKey(apiKey).build();

        when(apiKeyValidationService.validateApiKey(apiKey)).thenReturn(null);

        // When
        grpcService.validateApiKey(request, validateResponseObserver);

        // Then
        ArgumentCaptor<ValidateApiKeyResponse> responseCaptor =
                ArgumentCaptor.forClass(ValidateApiKeyResponse.class);

        verify(validateResponseObserver).onNext(responseCaptor.capture());
        verify(validateResponseObserver).onCompleted();
        verify(validateResponseObserver, never()).onError(any());

        ValidateApiKeyResponse response = responseCaptor.getValue();
        assertFalse(response.getIsValid());
        assertEquals("", response.getTenantId());
        assertEquals("", response.getStatus());
        assertEquals("", response.getPlan());
    }

    @Test
    @DisplayName("ValidateApiKey - Should return error when API key is empty")
    void validateApiKey_EmptyApiKeyInBuilder_ReturnsError() {
        // Given - protobuf doesn't allow null, use empty string
        ValidateApiKeyRequest request = ValidateApiKeyRequest.newBuilder().setApiKey("").build();

        // When
        grpcService.validateApiKey(request, validateResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(validateResponseObserver).onError(errorCaptor.capture());
        verify(validateResponseObserver, never()).onNext(any());
        verify(validateResponseObserver, never()).onCompleted();
        verify(apiKeyValidationService, never()).validateApiKey(anyString());

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        StatusRuntimeException statusException = (StatusRuntimeException) error;
        assertEquals(Status.Code.INVALID_ARGUMENT, statusException.getStatus().getCode());
        assertNotNull(statusException.getStatus().getDescription());
        assertTrue(
                statusException.getStatus().getDescription().contains("API key must be provided"));
    }

    @Test
    @DisplayName("ValidateApiKey - Should return error when API key is empty")
    void validateApiKey_EmptyApiKey_ReturnsError() {
        // Given
        ValidateApiKeyRequest request = ValidateApiKeyRequest.newBuilder().setApiKey("   ").build();

        // When
        grpcService.validateApiKey(request, validateResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(validateResponseObserver).onError(errorCaptor.capture());
        verify(validateResponseObserver, never()).onNext(any());
        verify(apiKeyValidationService, never()).validateApiKey(anyString());

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        assertEquals(
                Status.Code.INVALID_ARGUMENT,
                ((StatusRuntimeException) error).getStatus().getCode());
    }

    @Test
    @DisplayName("ValidateApiKey - Should return internal error when service throws exception")
    void validateApiKey_ServiceException_ReturnsInternalError() {
        // Given
        String apiKey = "sk_test_error_key";
        ValidateApiKeyRequest request =
                ValidateApiKeyRequest.newBuilder().setApiKey(apiKey).build();

        when(apiKeyValidationService.validateApiKey(apiKey))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When
        grpcService.validateApiKey(request, validateResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(validateResponseObserver).onError(errorCaptor.capture());
        verify(validateResponseObserver, never()).onNext(any());
        verify(validateResponseObserver, never()).onCompleted();

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        StatusRuntimeException statusException = (StatusRuntimeException) error;
        assertEquals(Status.Code.INTERNAL, statusException.getStatus().getCode());
        assertNotNull(statusException.getStatus().getDescription());
        assertTrue(statusException.getStatus().getDescription().contains("Internal error"));
    }

    // ===== CheckQuota Tests =====

    @Test
    @DisplayName("CheckQuota - Should return allowed when tenant has quota")
    void checkQuota_HasQuota_ReturnsAllowed() {
        // Given
        String tenantId = "tenant-123";
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId(tenantId).build();

        when(quotaService.hasAvailableQuota(tenantId)).thenReturn(true);
        when(quotaService.getRemainingQuota(tenantId)).thenReturn(500);
        when(quotaService.getQuotaLimit(tenantId)).thenReturn(1000);

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<CheckQuotaResponse> responseCaptor =
                ArgumentCaptor.forClass(CheckQuotaResponse.class);

        verify(quotaResponseObserver).onNext(responseCaptor.capture());
        verify(quotaResponseObserver).onCompleted();
        verify(quotaResponseObserver, never()).onError(any());

        CheckQuotaResponse response = responseCaptor.getValue();
        assertTrue(response.getIsAllowed());
        assertEquals(500, response.getRemaining());
        assertEquals(1000, response.getLimit());

        verify(quotaService).hasAvailableQuota(tenantId);
        verify(quotaService).getRemainingQuota(tenantId);
        verify(quotaService).getQuotaLimit(tenantId);
    }

    @Test
    @DisplayName("CheckQuota - Should return not allowed when tenant quota exceeded")
    void checkQuota_QuotaExceeded_ReturnsNotAllowed() {
        // Given
        String tenantId = "tenant-456";
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId(tenantId).build();

        when(quotaService.hasAvailableQuota(tenantId)).thenReturn(false);
        when(quotaService.getRemainingQuota(tenantId)).thenReturn(0);
        when(quotaService.getQuotaLimit(tenantId)).thenReturn(1000);

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<CheckQuotaResponse> responseCaptor =
                ArgumentCaptor.forClass(CheckQuotaResponse.class);

        verify(quotaResponseObserver).onNext(responseCaptor.capture());
        verify(quotaResponseObserver).onCompleted();

        CheckQuotaResponse response = responseCaptor.getValue();
        assertFalse(response.getIsAllowed());
        assertEquals(0, response.getRemaining());
        assertEquals(1000, response.getLimit());
    }

    @Test
    @DisplayName("CheckQuota - Should handle unlimited quota (-1)")
    void checkQuota_UnlimitedQuota_ReturnsAllowed() {
        // Given
        String tenantId = "tenant-unlimited";
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId(tenantId).build();

        when(quotaService.hasAvailableQuota(tenantId)).thenReturn(true);
        when(quotaService.getRemainingQuota(tenantId)).thenReturn(-1);
        when(quotaService.getQuotaLimit(tenantId)).thenReturn(-1);

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<CheckQuotaResponse> responseCaptor =
                ArgumentCaptor.forClass(CheckQuotaResponse.class);

        verify(quotaResponseObserver).onNext(responseCaptor.capture());
        verify(quotaResponseObserver).onCompleted();

        CheckQuotaResponse response = responseCaptor.getValue();
        assertTrue(response.getIsAllowed());
        assertEquals(-1, response.getRemaining());
        assertEquals(-1, response.getLimit());
    }

    @Test
    @DisplayName("CheckQuota - Should return error when tenant ID is empty")
    void checkQuota_EmptyTenantIdInBuilder_ReturnsError() {
        // Given - protobuf doesn't allow null, use empty string
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId("").build();

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(quotaResponseObserver).onError(errorCaptor.capture());
        verify(quotaResponseObserver, never()).onNext(any());
        verify(quotaResponseObserver, never()).onCompleted();

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        StatusRuntimeException statusException = (StatusRuntimeException) error;
        assertEquals(Status.Code.INVALID_ARGUMENT, statusException.getStatus().getCode());
        assertNotNull(statusException
                .getStatus()
                .getDescription());
        assertTrue(
                statusException
                        .getStatus()
                        .getDescription()
                        .contains("Tenant ID must be provided"));

        verify(quotaService, never()).hasAvailableQuota(anyString());
    }

    @Test
    @DisplayName("CheckQuota - Should return error when tenant ID is empty")
    void checkQuota_EmptyTenantId_ReturnsError() {
        // Given
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId("  ").build();

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(quotaResponseObserver).onError(errorCaptor.capture());
        verify(quotaResponseObserver, never()).onNext(any());

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        assertEquals(
                Status.Code.INVALID_ARGUMENT,
                ((StatusRuntimeException) error).getStatus().getCode());
    }

    @Test
    @DisplayName("CheckQuota - Should return internal error when service throws exception")
    void checkQuota_ServiceException_ReturnsInternalError() {
        // Given
        String tenantId = "tenant-error";
        CheckQuotaRequest request = CheckQuotaRequest.newBuilder().setTenantId(tenantId).build();

        when(quotaService.hasAvailableQuota(tenantId))
                .thenThrow(new RuntimeException("Database unavailable"));

        // When
        grpcService.checkQuota(request, quotaResponseObserver);

        // Then
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);

        verify(quotaResponseObserver).onError(errorCaptor.capture());
        verify(quotaResponseObserver, never()).onNext(any());
        verify(quotaResponseObserver, never()).onCompleted();

        Throwable error = errorCaptor.getValue();
        assertInstanceOf(StatusRuntimeException.class, error);
        StatusRuntimeException statusException = (StatusRuntimeException) error;
        assertEquals(Status.Code.INTERNAL, statusException.getStatus().getCode());
        assertNotNull(statusException.getStatus().getDescription());
        assertTrue(statusException.getStatus().getDescription().contains("Internal error"));
    }
}
