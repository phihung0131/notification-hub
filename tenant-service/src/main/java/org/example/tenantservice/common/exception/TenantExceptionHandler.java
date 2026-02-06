package org.example.tenantservice.common.exception;

import org.example.commons.baseclass.ApiResponse;
import org.example.commons.exception.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Tenant exception handler for all REST controllers across tenant services. */
@RestControllerAdvice
public class TenantExceptionHandler {

    /**
     * Handles Spring Security authentication failures.
     *
     * @param ex the BadCredentialsException from Spring Security
     * @return ResponseEntity with 401 status and generic authentication error
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleCredential(BadCredentialsException ex) {
        var err =
                ApiError.builder()
                        .code(HttpStatus.UNAUTHORIZED.value())
                        .message("Bad credentials")
                        .details("Invalid email or password")
                        .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(err));
    }
}
