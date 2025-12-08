package com.example.analyticsservice.config.logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Interceptor to log incoming HTTP requests and their outcomes
 */
@Slf4j
@Component
public class HistoryRequestInterceptor implements HandlerInterceptor {

    private static final String START_TIME = "startTime";

    /**
     * Log incoming request details and assign a traceId for tracking
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME, Instant.now());

        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = Optional.ofNullable(request.getQueryString()).orElse("");
        String remoteAddr = request.getRemoteAddr();

        log.info("[INCOMING REST] [{}] {}?{} from {}", method, path, query, remoteAddr);
        return true;
    }

    /**
     * Log the outcome of the request, including status and duration
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        Instant start = (Instant) request.getAttribute(START_TIME);
        long duration = start != null ? Duration.between(start, Instant.now()).toMillis() : -1;
        int status = response.getStatus();

        if (ex != null) {
            log.error("[COMPLETED REST] [{}] {} | status={} | duration={}ms | ERROR={}",
                    request.getMethod(), request.getRequestURI(), status, duration, ex.getMessage(), ex);
        } else {
            log.info("[COMPLETED REST] [{}] {} | status={} | duration={}ms",
                    request.getMethod(), request.getRequestURI(), status, duration);
        }
    }
}
