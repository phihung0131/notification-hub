package org.example.tenantservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Standard API error messages with codes and HTTP status
 */
@AllArgsConstructor
@Getter
public class ApiErrorMessage {

    public static ApiErrorMessage
        EMAIL_NOT_AVAILABLE = new ApiErrorMessage(1000001, "Email is already in use.", HttpStatus.NOT_FOUND),
        INVALID_API_KEY = new ApiErrorMessage(1000002, "Invalid API Key.", HttpStatus.UNAUTHORIZED),
        PERMISSION_ALREADY_EXISTS = new ApiErrorMessage(1000003, "Permission already exists.", HttpStatus.BAD_REQUEST),
        PERMISSION_NOT_FOUND = new ApiErrorMessage(1000004, "Permission not found.", HttpStatus.NOT_FOUND),
        TENANT_NOT_FOUND = new ApiErrorMessage(1000005, "Tenant not found.", HttpStatus.NOT_FOUND),
        UNAUTHORIZED = new ApiErrorMessage(1000006, "Unauthorized.", HttpStatus.UNAUTHORIZED),
        API_KEY_NOT_FOUND = new ApiErrorMessage(1000007, "API Key not found.", HttpStatus.NOT_FOUND),
        FORBIDDEN = new ApiErrorMessage(1000008, "Forbidden.", HttpStatus.FORBIDDEN);
    protected final int code;
    protected final String message;
    protected final HttpStatus status;
}
