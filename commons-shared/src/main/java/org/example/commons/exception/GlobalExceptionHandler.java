package org.example.commons.exception;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.example.commons.baseclass.ApiResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Global exception handler for all REST controllers across notification-hub services. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles application-specific {@link BaseException} instances.
     *
     * @param ex the BaseException thrown by application code
     * @return ResponseEntity with appropriate status code and error details
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBase(BaseException ex) {
        var err =
                ApiError.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .details(ex.getDetails())
                        .build();
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.fail(err));
    }

    /**
     * Handles bean validation failures from {@code @Valid} or {@code @Validated} annotations.
     *
     * @param ex the MethodArgumentNotValidException containing field errors
     * @return ResponseEntity with 400 status and detailed validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(f -> Map.of("field", f.getField(), "message", f.getDefaultMessage()))
                        .toList();
        var err =
                ApiError.builder()
                        .code(HttpStatus.BAD_REQUEST.value())
                        .message("Validation failed")
                        .details(fieldErrors)
                        .build();
        return ResponseEntity.badRequest().body(ApiResponse.fail(err));
    }

    /**
     * Handles database constraint violations (unique, foreign key, check constraints).
     *
     * @param ex the ConstraintViolationException from Hibernate
     * @return ResponseEntity with 400 status and constraint violation details
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        var err =
                ApiError.builder()
                        .code(HttpStatus.BAD_REQUEST.value())
                        .message("Constraint violation")
                        .details(
                                ex.getConstraintName() != null
                                        ? ex.getConstraintName()
                                        : "Unknown constraint")
                        .build();
        return ResponseEntity.badRequest().body(ApiResponse.fail(err));
    }

    /**
     * Catch-all handler for unexpected exceptions not handled by specific handlers.
     *
     * @param ex the unexpected exception
     * @param req the HTTP request that caused the exception (for logging context)
     * @return ResponseEntity with 500 status and generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex, HttpServletRequest req) {
        var err =
                ApiError.builder()
                        .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .message("Unexpected error")
                        .details(ex.getMessage())
                        .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(err));
    }
}
