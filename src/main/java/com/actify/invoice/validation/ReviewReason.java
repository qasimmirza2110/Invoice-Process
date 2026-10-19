package com.actify.invoice.validation;

/** One problem found in an extracted invoice: a code for counting, the field it is about, and a readable message. */
public final class ReviewReason {

    private final ReviewReasonCode code;
    private final String field;
    private final String message;

    public ReviewReason(ReviewReasonCode code, String field, String message) {
        this.code = code;
        this.field = field;
        this.message = message;
    }

    public ReviewReasonCode getCode() { return code; }
    public String getField() { return field; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return code + " " + field + ": " + message;
    }
}
