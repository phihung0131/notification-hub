package org.example.commons.baseclass;

import java.time.Instant;

import org.example.commons.exception.ApiError;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Standard API response wrapper for all REST endpoints across notification-hub services.
 *
 * @param <T> the type of the response data payload
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApiResponse<T> {

    /** Indicates whether the operation was successful. */
    private boolean success;

    /** The response payload for successful operations. */
    private T data;

    /** Error details for failed operations. */
    private ApiError error;

    /** OpenTelemetry trace ID for correlating this response with distributed traces. */
    private String traceId;

    /** OpenTelemetry span ID for this specific service's processing. */
    private String spanId;

    /** Timestamp when this response was created. */
    private Instant ts;

    /**
     * Creates a successful API response with data payload.
     *
     * @param <T> the type of the data payload
     * @param data the response data (can be null for operations without return value)
     * @return a successful ApiResponse with the provided data
     */
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

    /**
     * Creates a failed API response with error details.
     *
     * @param <T> the type parameter (usually Void for error responses)
     * @param err the error details describing what went wrong
     * @return a failed ApiResponse with the provided error
     */
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
