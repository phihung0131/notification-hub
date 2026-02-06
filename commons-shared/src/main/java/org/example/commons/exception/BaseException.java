package org.example.commons.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** Base exception class for all custom application exceptions in notification-hub services. */
@Getter
public class BaseException extends RuntimeException {

    /** Application-specific error code for programmatic error handling. */
    private final Integer code;

    /** HTTP status code to return in the API response. */
    private final HttpStatus status;

    /** Optional detailed information about the error context. */
    private final Object details;

    /**
     * Constructs a new BaseException with all fields explicitly specified.
     *
     * @param code Application error code (must be in service's range)
     * @param status HTTP status code for the response
     * @param message Error message describing what went wrong
     * @param details Optional additional context (can be null)
     */
    public BaseException(Integer code, HttpStatus status, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    /**
     * Constructs a new BaseException from a service-specific error message enum.
     *
     * @param errorMessage Service-specific error message object with code, status, and message
     * @throws IllegalArgumentException if errorMessage doesn't have required methods
     */
    public BaseException(Object errorMessage) {
        this(
                extractCode(errorMessage),
                extractStatus(errorMessage),
                extractMessage(errorMessage),
                null);
    }

    /** Extracts error code from error message object using reflection. */
    private static Integer extractCode(Object errorMessage) {
        try {
            var method = errorMessage.getClass().getMethod("getCode");
            return (Integer) method.invoke(errorMessage);
        } catch (Exception e) {
            throw new IllegalArgumentException("errorMessage must have getCode() method", e);
        }
    }

    /** Extracts HTTP status from error message object using reflection. */
    private static HttpStatus extractStatus(Object errorMessage) {
        try {
            var method = errorMessage.getClass().getMethod("getStatus");
            return (HttpStatus) method.invoke(errorMessage);
        } catch (Exception e) {
            throw new IllegalArgumentException("errorMessage must have getStatus() method", e);
        }
    }

    /** Extracts error message from error message object using reflection. */
    private static String extractMessage(Object errorMessage) {
        try {
            var method = errorMessage.getClass().getMethod("getMessage");
            return (String) method.invoke(errorMessage);
        } catch (Exception e) {
            throw new IllegalArgumentException("errorMessage must have getMessage() method", e);
        }
    }
}
