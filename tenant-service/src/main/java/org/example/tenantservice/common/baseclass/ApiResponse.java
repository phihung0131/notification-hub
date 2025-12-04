package org.example.tenantservice.common.baseclass;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.*;
import org.example.tenantservice.common.exception.ApiError;

import java.time.Instant;

/**
 * Standard API response wrapper
 * @param <T> the type of the response data
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private ApiError error;
    private String traceId;
    private String spanId;
    private Instant ts;

    public static <T> ApiResponse<T> ok(T data) {
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .traceId(spanContext.getTraceId())
                .spanId(spanContext.getSpanId())
                .ts(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> fail(ApiError err) {
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        return ApiResponse.<T>builder()
                .success(false)
                .error(err)
                .traceId(spanContext.getTraceId())
                .spanId(spanContext.getSpanId())
                .ts(Instant.now())
                .build();
    }
}