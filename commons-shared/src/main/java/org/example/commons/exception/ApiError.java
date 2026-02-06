package org.example.commons.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Standard API error response structure used across all services. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiError {

    /** Application-specific error code for programmatic error handling. */
    private Integer code;

    /** Human-readable error message describing what went wrong. */
    private String message;

    /** Optional detailed information about the error. */
    private Object details;
}
