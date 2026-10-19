package com.actify.invoice.exception;

import com.actify.invoice.dto.FieldErrorDto;

import java.util.List;

/** The correction request itself is malformed (unknown/forbidden fields, missing baseVersion...). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    private final List<FieldErrorDto> fieldErrors;

    public InvalidRequestException(String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
