package org.example.notificationservice.common.exception;

import lombok.*;

/**
 * Standard API error response
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiError {
    private Integer code; // error code
    private String message; // error message
    private Object details;  // field errors, stack short, ...
}
