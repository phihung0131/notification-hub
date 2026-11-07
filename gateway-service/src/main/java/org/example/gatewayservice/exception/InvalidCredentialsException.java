package org.example.gatewayservice.exception;

/** The API key or JWT was definitively rejected by tenant-service. Maps to HTTP 401. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
