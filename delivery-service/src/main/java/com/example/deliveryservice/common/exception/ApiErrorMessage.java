package com.example.deliveryservice.common.exception;

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
        CHANNEL_NOT_FOUND = new ApiErrorMessage(2000001, "Channel not found.", HttpStatus.NOT_FOUND),
        QUOTA_EXCEEDED = new ApiErrorMessage(2000002, "Notification quota exceeded.", HttpStatus.FORBIDDEN);
    protected final int code;
    protected final String message;
    protected final HttpStatus status;
}
