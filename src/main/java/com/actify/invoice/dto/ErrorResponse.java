package com.actify.invoice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** One error shape for every failure, so API clients only have to handle one format. */
public class ErrorResponse {

    private final LocalDateTime timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    // Only for errors about specific input fields (the invoice edit form). Omitted from the JSON
    // otherwise, so every existing error response keeps exactly its previous shape.
    private final List<FieldErrorDto> fieldErrors;

    public ErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, null);
    }

    public ErrorResponse(int status, String error, String message, String path, List<FieldErrorDto> fieldErrors) {
        this.timestamp = LocalDateTime.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.fieldErrors = fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public String getError() { return error; }
    public String getMessage() { return message; }
    public String getPath() { return path; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<FieldErrorDto> getFieldErrors() { return fieldErrors; }
}
