package org.example.notificationservice.util;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Utility để lấy traceId và tenantId từ tracing context
 * Hoạt động với REST, gRPC, và Kafka
 */
@Component
@RequiredArgsConstructor
public class TracingContextUtil {

    private final Tracer tracer;

    /**
     * Lấy traceId từ Micrometer (OpenTelemetry format)
     */
    public String getTraceId() {
        // ✅ Ưu tiên lấy từ Micrometer
        String traceId = Optional.ofNullable(tracer.currentSpan())
                .map(Span::context)
                .map(ctx -> ctx.traceId())
                .orElse(null);

        // Fallback: lấy từ MDC
        if (traceId == null) {
            traceId = MDC.get("traceId");
        }

        return traceId;
    }

    /**
     * Lấy spanId từ Micrometer
     */
    public String getSpanId() {
        String spanId = Optional.ofNullable(tracer.currentSpan())
                .map(Span::context)
                .map(ctx -> ctx.spanId())
                .orElse(null);

        if (spanId == null) {
            spanId = MDC.get("spanId");
        }

        return spanId;
    }

    /**
     * Lấy tenantId từ context hiện tại
     */
    public String getTenantId() {
        // Thử lấy từ MDC trước (đã được set bởi baggage.correlation)
        String tenantId = MDC.get("tenantId");
        if (tenantId != null) {
            return tenantId;
        }

        // Fallback: lấy từ span tags
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> getSpanTag(span, "tenantId"))
                .orElse(null);
    }

    /**
     * Lấy cả traceId và tenantId
     */
    public TracingContext getCurrentContext() {
        return new TracingContext(getTraceId(), getTenantId());
    }

    /**
     * Set tenantId vào context (hữu ích khi xử lý async/Kafka)
     */
    public void setTenantId(String tenantId) {
        if (tenantId == null) return;

        MDC.put("tenantId", tenantId);

        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("tenantId", tenantId);
        }
    }

    /**
     * Set traceId vào context
     */
    public void setTraceId(String traceId) {
        if (traceId == null) return;

        MDC.put("traceId", traceId);

        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("traceId", traceId);
        }
    }

    /**
     * Helper để lấy tag từ span
     */
    private String getSpanTag(Span span, String key) {
        try {
            // Note: Span API không có getter cho tags
            // Tags chỉ dùng để export ra tracing backend
            // Nên best practice là dùng MDC
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Record class chứa tracing context
     */
    public record TracingContext(String traceId, String tenantId) {
        public boolean hasTenantId() {
            return tenantId != null && !tenantId.isBlank();
        }
    }
}