package org.example.notificationservice.common.baseclass;

import lombok.*;
import org.example.notificationservice.common.exception.ApiError;

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
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .traceId(TraceIdHolder.getTraceId())
                .spanId(TraceIdHolder.getSpanId())
                .ts(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> fail(ApiError err) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(err)
                .traceId(TraceIdHolder.getTraceId())
                .spanId(TraceIdHolder.getSpanId())
                .ts(Instant.now())
                .build();
    }
}