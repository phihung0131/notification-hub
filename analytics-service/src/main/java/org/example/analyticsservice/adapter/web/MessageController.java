package org.example.analyticsservice.adapter.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.example.analyticsservice.application.MessageQueryService;
import org.example.analyticsservice.domain.MessagePage;
import org.example.analyticsservice.domain.MessageSearchCriteria;
import org.example.commons.baseclass.ApiResponse;
import org.example.commons.exception.ApiError;
import org.example.events.enums.NotificationStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.RequiredArgsConstructor;

/** Tenant-scoped read API (tenant comes from the gateway-injected X-Tenant-Id header). */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final MessageQueryService queryService;

    @GetMapping("/messages/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> getMessage(
            @PathVariable("id") UUID id, @RequestHeader("X-Tenant-Id") String tenantId) {
        return queryService
                .getById(tenantId, id)
                .map(m -> ResponseEntity.ok(ApiResponse.ok(MessageResponse.from(m))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Keyset-paginated list, newest first.
     *
     * @param cursor opaque value from the previous page's nextCursor
     * @param limit page size (default 50, max 200)
     * @param from created_at lower bound, inclusive (ISO-8601)
     * @param to created_at upper bound, exclusive (ISO-8601)
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<MessagePageResponse>> listMessages(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        MessagePage page =
                queryService.search(
                        new MessageSearchCriteria(tenantId, status, channel, from, to),
                        cursor,
                        limit);
        return ResponseEntity.ok(
                ApiResponse.ok(
                        new MessagePageResponse(
                                page.items().stream().map(MessageResponse::from).toList(),
                                page.nextCursor())));
    }

    /** Counts by status for the tenant, optionally within a created_at range. */
    @GetMapping("/messages/stats")
    public ResponseEntity<ApiResponse<Map<NotificationStatus, Long>>> stats(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.stats(tenantId, from, to)));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> badRequest(Exception e) {
        ApiError err = new ApiError(400, "Invalid request parameter", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(err));
    }
}
