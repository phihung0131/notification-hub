package org.example.tenantservice.common.baseclass;

import lombok.*;
import org.example.tenantservice.common.exception.ApiError;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private Instant ts;

    private static String traceId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? (String) attrs.getRequest().getAttribute("traceId") : null;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true).data(data).traceId(traceId()).ts(Instant.now()).build();
    }

    public static <T> ApiResponse<T> fail(ApiError err) {
        return ApiResponse.<T>builder()
                .success(false).error(err).traceId(traceId()).ts(Instant.now()).build();
    }
}
