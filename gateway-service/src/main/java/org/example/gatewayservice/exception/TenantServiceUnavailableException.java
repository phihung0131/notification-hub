package org.example.gatewayservice.exception;

/** tenant-service could not give a verdict (down, slow, errored). Maps to HTTP 503, never 401. */
public class TenantServiceUnavailableException extends RuntimeException {

    public TenantServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
