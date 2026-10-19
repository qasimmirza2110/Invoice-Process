package com.actify.invoice.exception;

import com.actify.invoice.dto.FieldErrorDto;

import java.util.Collections;
import java.util.List;

/**
 * The invoice values failed our business checks: either the LLM's answer, or a user's correction.
 * Maps to HTTP 422. fieldErrors is only filled for corrections (so the form can mark each field).
 */
public class InvoiceValidationException extends RuntimeException {

    private final List<FieldErrorDto> fieldErrors;

    public InvoiceValidationException(String message) {
        this(message, Collections.<FieldErrorDto>emptyList());
    }

    public InvoiceValidationException(String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }
}
