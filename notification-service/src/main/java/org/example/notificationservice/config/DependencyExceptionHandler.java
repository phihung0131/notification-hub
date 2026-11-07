package org.example.notificationservice.config;

import org.example.commons.baseclass.ApiResponse;
import org.example.commons.exception.ApiError;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/** Fail-closed mapping: a required dependency is down, so answer 503 (never accept blindly). */
@RestControllerAdvice
@Slf4j
public class DependencyExceptionHandler {

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handle(DependencyUnavailableException ex) {
        log.error("Failing closed, dependency down: {}", ex.getDependency(), ex);
        ApiErrorMessage msg =
                "quota-service".equals(ex.getDependency())
                        ? ApiErrorMessage.QUOTA_UNAVAILABLE
                        : ApiErrorMessage.DEPENDENCY_UNAVAILABLE;
        ApiError err = ApiError.builder().code(msg.getCode()).message(msg.getMessage()).build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.fail(err));
    }
}
