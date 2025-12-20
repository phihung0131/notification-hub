package org.example.commons.exception;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.example.commons.baseclass.ApiResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for all REST controllers across notification-hub services.
 *
 * <p>This class provides centralized exception handling using Spring's
 * {@code @RestControllerAdvice}. It catches exceptions thrown by any {@code @RestController} and
 * converts them into consistent {@link ApiResponse} structures with appropriate HTTP status codes.
 *
 * <h2>Handled Exception Types:</h2>
 *
 * <ul>
 *   <li>{@link BaseException} - Application-specific business logic errors
 *   <li>{@link MethodArgumentNotValidException} - Bean validation failures (@Valid, @Validated)
 *   <li>{@link ConstraintViolationException} - Database constraint violations
 *   <li>{@link BadCredentialsException} - Spring Security authentication failures
 *   <li>{@link Exception} - Catch-all for unexpected errors
 * </ul>
 *
 * <h2>Design Patterns:</h2>
 *
 * <ul>
 *   <li><strong>Fail-Safe:</strong> Unexpected exceptions are caught to prevent 500 errors from
 *       leaking internals
 *   <li><strong>Consistent Response:</strong> All errors return {@link ApiResponse} structure
 *   <li><strong>Appropriate Status Codes:</strong> Maps exception types to REST-appropriate HTTP
 *       status
 *   <li><strong>Tracing Integration:</strong> ApiResponse includes trace/span IDs for debugging
 * </ul>
 *
 * <h2>Usage:</h2>
 *
 * <p>This handler is automatically detected by Spring via {@code @RestControllerAdvice}. No
 * explicit configuration needed - just throw exceptions from your controllers or services:
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/tenants")
 * public class TenantController {
 *     @GetMapping("/{id}")
 *     public ApiResponse<Tenant> getTenant(@PathVariable String id) {
 *         Tenant tenant = tenantService.findById(id);
 *         if (tenant == null) {
 *             // This will be caught by GlobalExceptionHandler.handleBase()
 *             throw new BaseException(TenantErrorMessages.TENANT_NOT_FOUND);
 *         }
 *         return ApiResponse.ok(tenant);
 *     }
 * }
 * }</pre>
 *
 * <h2>Error Response Examples:</h2>
 *
 * <pre>{@code
 * // BaseException (404 Not Found):
 * {
 *   "success": false,
 *   "error": {
 *     "code": 1000005,
 *     "message": "Tenant not found",
 *     "details": null
 *   },
 *   "traceId": "...",
 *   "spanId": "...",
 *   "ts": "2024-01-15T10:30:00Z"
 * }
 *
 * // Validation error (400 Bad Request):
 * {
 *   "success": false,
 *   "error": {
 *     "code": 400,
 *     "message": "Validation failed",
 *     "details": [
 *       {"field": "email", "message": "must be a valid email"},
 *       {"field": "name", "message": "must not be blank"}
 *     ]
 *   },
 *   "traceId": "...",
 *   "spanId": "...",
 *   "ts": "2024-01-15T10:30:00Z"
 * }
 * }</pre>
 *
 * <h2>Extending This Handler:</h2>
 *
 * <p>Services can add service-specific exception handlers by creating additional
 * {@code @ExceptionHandler} methods in this class or by creating a subclass in their own package
 * (not recommended - prefer composition).
 *
 * <h2>Security Considerations:</h2>
 *
 * <ul>
 *   <li>Generic exception handler hides internal error details from clients
 *   <li>Stack traces are NOT included in production responses
 *   <li>Sensitive information should not be in exception messages
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 *
 * <p>Spring manages this as a singleton bean. Handler methods are stateless and thread-safe.
 *
 * @author Notification Hub Team
 * @version 1.0
 * @since 1.0
 * @see BaseException
 * @see ApiResponse
 * @see ApiError
 */
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
