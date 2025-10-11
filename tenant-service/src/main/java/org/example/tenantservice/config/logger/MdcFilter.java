package org.example.tenantservice.config.logger;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class MdcFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var req = (HttpServletRequest) request;
        String traceId = req.getHeader("X-Request-Id");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();

        // put into MDC
        MDC.put("traceId", traceId);

        // assign for ExceptionHandler use
        req.setAttribute("traceId", traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}