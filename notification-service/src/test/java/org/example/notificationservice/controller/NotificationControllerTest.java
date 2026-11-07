package org.example.notificationservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.commons.exception.BaseException;
import org.example.commons.exception.GlobalExceptionHandler;
import org.example.notificationservice.application.SendCommand;
import org.example.notificationservice.application.SendNotificationUseCase;
import org.example.notificationservice.application.SendResult;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.config.DependencyExceptionHandler;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link NotificationController}. Tests REST API endpoints for sending
 * notifications.
 *
 * @author Notification Hub Team
 */
class NotificationControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SendNotificationUseCase orchestrationService =
            org.mockito.Mockito.mock(SendNotificationUseCase.class);

    @BeforeEach
    void setUp() {
        // standalone: the app-level @ComponentScan makes @WebMvcTest load every bean
        mockMvc =
                MockMvcBuilders.standaloneSetup(new NotificationController(orchestrationService))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(), new DependencyExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("POST /send - Should accept valid notification request and return 202")
    void send_ValidRequest_ReturnsAccepted() throws Exception {
        // Given
        String tenantId = "tenant-123";
        String messageId = "msg-uuid-456";

        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setSubject("Test Subject");
        request.setContent("Test Content");

        SendNotificationResponse expectedResponse =
                SendNotificationResponse.builder()
                        .id(messageId)
                        .tenantId(tenantId)
                        .notificationStatus(NotificationStatus.PENDING)
                        .message("Notification request accepted and queued for processing")
                        .build();

        when(orchestrationService.send(any(SendCommand.class)))
                .thenReturn(new SendResult(expectedResponse, false));

        // When & Then
        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(messageId))
                .andExpect(jsonPath("$.data.tenantId").value(tenantId))
                .andExpect(jsonPath("$.data.notificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.message").value(containsString("accepted")))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.spanId").exists())
                .andExpect(jsonPath("$.ts").exists());

        verify(orchestrationService).send(any(SendCommand.class));
    }

    @Test
    @DisplayName("POST /send - Should return error when X-Tenant-Id header is missing")
    void send_MissingTenantHeader_ReturnsError() throws Exception {
        // Given
        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setSubject("Test");
        request.setContent("Content");

        // When & Then
        mockMvc.perform(
                        post("/send")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError()); // Missing required header causes 500

        verify(orchestrationService, never()).send(any());
    }

    @Test
    @DisplayName("POST /send - Should return error when request body is invalid")
    void send_InvalidRequestBody_ReturnsError() throws Exception {
        // Given
        String tenantId = "tenant-123";
        String invalidJson = "{ invalid json }";

        // When & Then
        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidJson))
                .andExpect(status().is5xxServerError()); // JSON parse error causes 500

        verify(orchestrationService, never()).send(any());
    }

    @Test
    @DisplayName("POST /send - Should return 429 when quota exceeded")
    void send_QuotaExceeded_ReturnsTooManyRequests() throws Exception {
        // Given
        String tenantId = "tenant-123";

        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setSubject("Test");
        request.setContent("Content");

        when(orchestrationService.send(any(SendCommand.class)))
                .thenThrow(
                        new BaseException(
                                2000003,
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Notification quota exceeded.",
                                null));

        // When & Then
        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value(2000003))
                .andExpect(jsonPath("$.error.message").value("Notification quota exceeded."))
                .andExpect(jsonPath("$.traceId").exists());

        verify(orchestrationService).send(any(SendCommand.class));
    }

    @Test
    @DisplayName("POST /send - Should return 404 when channel not found")
    void send_ChannelNotFound_ReturnsNotFound() throws Exception {
        // Given
        String tenantId = "tenant-123";

        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("INVALID_CHANNEL");
        request.setRecipient("user@example.com");
        request.setSubject("Test");
        request.setContent("Content");

        when(orchestrationService.send(any(SendCommand.class)))
                .thenThrow(
                        new BaseException(
                                2000001, HttpStatus.NOT_FOUND, "Channel not found.", null));

        // When & Then
        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(2000001))
                .andExpect(jsonPath("$.error.message").value("Channel not found."));

        verify(orchestrationService).send(any(SendCommand.class));
    }

    @Test
    @DisplayName("POST /send - Should handle different channel types")
    void send_DifferentChannels_AcceptsAll() throws Exception {
        // Given
        String tenantId = "tenant-123";
        String[] channels = {"EMAIL", "SMS", "TELEGRAM"};

        for (String channel : channels) {
            reset(orchestrationService);

            SendNotificationRequest request = new SendNotificationRequest();
            request.setChannel(channel);
            request.setRecipient("recipient");
            request.setSubject("Subject");
            request.setContent("Content");

            SendNotificationResponse response =
                    SendNotificationResponse.builder()
                            .id("msg-" + channel)
                            .tenantId(tenantId)
                            .notificationStatus(NotificationStatus.PENDING)
                            .message("Accepted")
                            .build();

            when(orchestrationService.send(any(SendCommand.class)))
                    .thenReturn(new SendResult(response, false));

            // When & Then
            mockMvc.perform(
                            post("/send")
                                    .header("X-Tenant-Id", tenantId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("msg-" + channel));

            verify(orchestrationService).send(any(SendCommand.class));
        }
    }

    @Test
    @DisplayName("POST /send - Idempotency-Key is passed to the use case and replay is flagged")
    void send_IdempotencyKey_PassedAndReplayHeaderSet() throws Exception {
        String tenantId = "tenant-123";
        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setContent("Content");

        SendNotificationResponse resp =
                SendNotificationResponse.builder()
                        .id("orig-id")
                        .tenantId(tenantId)
                        .notificationStatus(NotificationStatus.PENDING)
                        .message("Accepted")
                        .build();
        when(orchestrationService.send(any(SendCommand.class)))
                .thenReturn(new SendResult(resp, true));

        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", tenantId)
                                .header("Idempotency-Key", "key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.data.id").value("orig-id"));

        org.mockito.ArgumentCaptor<SendCommand> cap =
                org.mockito.ArgumentCaptor.forClass(SendCommand.class);
        verify(orchestrationService).send(cap.capture());
        org.junit.jupiter.api.Assertions.assertEquals("key-1", cap.getValue().idempotencyKey());
        org.junit.jupiter.api.Assertions.assertEquals(tenantId, cap.getValue().tenantId());
    }

    @Test
    @DisplayName("POST /send - Oversized Idempotency-Key is rejected with 400")
    void send_InvalidIdempotencyKey_Returns400() throws Exception {
        SendNotificationRequest request = new SendNotificationRequest();
        request.setChannel("EMAIL");
        request.setRecipient("user@example.com");
        request.setContent("Content");

        mockMvc.perform(
                        post("/send")
                                .header("X-Tenant-Id", "t")
                                .header("Idempotency-Key", "x".repeat(256))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verify(orchestrationService, never()).send(any());
    }
}
