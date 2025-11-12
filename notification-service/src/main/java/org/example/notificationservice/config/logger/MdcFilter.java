package org.example.notificationservice.config.logger;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;

@RequiredArgsConstructor
@Component
public class MdcFilter implements Filter {

    private final Tracer tracer;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;

        String tenantId = req.getHeader("X-Tenant-Id");

        Span currentSpan = tracer.currentSpan();

        if (currentSpan != null) {
            String traceId = currentSpan.context().traceId();
            String spanId = currentSpan.context().spanId();

            if (tenantId != null) {
                currentSpan.tag("tenantId", tenantId);
            }

            MDC.put("traceId", traceId);
            MDC.put("spanId", spanId);
            if (tenantId != null) {
                MDC.put("tenantId", tenantId);
            }

            req.setAttribute("traceId", traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}