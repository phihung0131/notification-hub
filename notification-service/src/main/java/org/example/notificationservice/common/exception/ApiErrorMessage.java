package org.example.notificationservice.common.exception;

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
        EMAIL_NOT_AVAILABLE = new ApiErrorMessage(1000001, "Email is already in use.", HttpStatus.NOT_FOUND);
    protected final int code;
    protected final String message;
    protected final HttpStatus status;
}
