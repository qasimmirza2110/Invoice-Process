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
    // Only on unexpected 500s: a short random reference that is also written to the server log line, so a user can
    // report it and a developer can find the stack trace. It carries no internal detail.
    private String errorId;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getErrorId() { return errorId; }

    public ErrorResponse withErrorId(String errorId) {
        this.errorId = errorId;
        return this;
    }
}
