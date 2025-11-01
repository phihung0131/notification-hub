package org.example.notificationservice.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception class for custom exceptions
 */
@Getter
public class BaseException extends RuntimeException {
    private final Integer code;
    private final HttpStatus status;
    private final Object details;

    public BaseException(Integer code, HttpStatus status, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public BaseException(ApiErrorMessage errorMessage) {
        this(errorMessage.getCode(), errorMessage.getStatus(), errorMessage.getMessage(), null);
    }
}
