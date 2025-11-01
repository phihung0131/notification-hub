package org.example.notificationservice.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.example.notificationservice.common.baseclass.ApiResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for the application
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBase(BaseException ex) {
        var err = ApiError.builder()
                .code(ex.getCode())
                .message(ex.getMessage())
                .details(ex.getDetails())
                .build();
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(err));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", f.getDefaultMessage()))
                .toList();
        var err = ApiError.builder()
                .code(HttpStatus.BAD_REQUEST.value())
                .message("Validation failed")
                .details(fieldErrors)
                .build();
        return ResponseEntity.badRequest().body(ApiResponse.fail(err));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        var err = ApiError.builder()
                .code(HttpStatus.BAD_REQUEST.value())
                .message("Constraint violation")
                .details(ex.getConstraintName().toString())
                .build();
        return ResponseEntity.badRequest().body(ApiResponse.fail(err));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleCredential(BadCredentialsException ex) {
        var err = ApiError.builder()
                .code(HttpStatus.UNAUTHORIZED.value())
                .message("Bad credentials")
                .details("Invalid email or password")
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(err));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex, HttpServletRequest req) {
        var err = ApiError.builder()
                .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .message("Unexpected error")
                .details(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(err));
    }
}