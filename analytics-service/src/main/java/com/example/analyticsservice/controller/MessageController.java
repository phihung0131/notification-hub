package com.example.analyticsservice.controller;

import java.util.List;
import java.util.UUID;

import org.example.commons.baseclass.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.analyticsservice.dto.response.MessageResponse;
import com.example.analyticsservice.service.MessageQueryService;

import lombok.RequiredArgsConstructor;

/** REST controller for message analytics and status queries. */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final MessageQueryService messageQueryService;

    /**
     * Retrieves a single message by ID with tenant isolation.
     *
     * @param id message ID (UUID)
     * @param tenantId tenant ID from X-Tenant-Id header
     * @return message details or 404 Not Found
     */
    @GetMapping("/messages/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> getMessage(
            @PathVariable("id") UUID id, @RequestHeader("X-Tenant-Id") String tenantId) {
        MessageResponse res = messageQueryService.getById(id);
        if (res == null || !tenantId.equals(res.getTenantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(res));
    }

    /**
     * Lists all messages for the authenticated tenant.
     *
     * @param tenantId tenant ID from X-Tenant-Id header
     * @return list of messages for the tenant
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> listMessages(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        List<MessageResponse> list = messageQueryService.listByTenant(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }
}
