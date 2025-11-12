package org.example.notificationservice.common.baseclass;

import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class TraceIdHolder {
    private static Tracer tracer;

    @Autowired
    public void setTracer(Tracer tracer) {
        TraceIdHolder.tracer = tracer;
    }

    /**
     * Lấy traceId từ Micrometer (OpenTelemetry format)
     * Fallback về MDC nếu không có
     */
    public static String getTraceId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().traceId();
        }
        // Fallback: lấy từ MDC
        return MDC.get("traceId");
    }

    /**
     * Lấy spanId từ Micrometer
     */
    public static String getSpanId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().spanId();
        }
        return MDC.get("spanId");
    }
}