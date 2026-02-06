package org.example.gatewayservice.common.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Standard API error messages with codes and HTTP status */
@AllArgsConstructor
@Getter
public class ApiErrorMessage {

    public static ApiErrorMessage AUTHENTICATE_VALIDATION_FAILED =
            new ApiErrorMessage(
                    6000001, "Authenticate validation failed.", HttpStatus.UNAUTHORIZED);

    protected final int code;
    protected final String message;
    protected final HttpStatus status;
}
