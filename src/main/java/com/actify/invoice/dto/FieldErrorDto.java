package com.actify.invoice.dto;

/** One problem with one input field, e.g. {"field": "items[0].quantity", "message": "items[0].quantity must be greater than 0"}. */
public class FieldErrorDto {

    private final String field;
    private final String message;

    public FieldErrorDto(String field, String message) {
        this.field = field;
        this.message = message;
    }

    public String getField() { return field; }
    public String getMessage() { return message; }
}
