package org.example.notificationservice.common.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Standard API error messages with codes and HTTP status */
@AllArgsConstructor
@Getter
public class ApiErrorMessage {

    public static ApiErrorMessage
            CHANNEL_NOT_FOUND =
                    new ApiErrorMessage(2000001, "Channel not found.", HttpStatus.NOT_FOUND),
            CHANNEL_INACTIVE =
                    new ApiErrorMessage(2000002, "Channel is inactive.", HttpStatus.BAD_REQUEST),
            QUOTA_EXCEEDED =
                    new ApiErrorMessage(
                            2000003, "Notification quota exceeded.", HttpStatus.TOO_MANY_REQUESTS),
            INVALID_RECIPIENT =
                    new ApiErrorMessage(2000004, "Invalid recipient.", HttpStatus.BAD_REQUEST),
            INVALID_EMAIL_FORMAT =
                    new ApiErrorMessage(2000005, "Invalid email format.", HttpStatus.BAD_REQUEST),
            INVALID_PHONE_FORMAT =
                    new ApiErrorMessage(2000006, "Invalid phone format.", HttpStatus.BAD_REQUEST),
            EMPTY_CONTENT =
                    new ApiErrorMessage(
                            2000007, "Content cannot be empty.", HttpStatus.BAD_REQUEST),
            CONTENT_TOO_LONG =
                    new ApiErrorMessage(
                            2000008, "Content exceeds maximum length.", HttpStatus.BAD_REQUEST);

    protected final int code;
    protected final String message;
    protected final HttpStatus status;
}
