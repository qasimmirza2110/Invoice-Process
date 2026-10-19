package com.actify.invoice.exception;

/**
 * The local Gemini budget (requests or tokens per minute, or requests per day) is used up, so the call was NOT
 * made. A subclass of InvoiceExtractionException so the legacy flow marks the upload FAILED as before;
 * GlobalExceptionHandler answers it with HTTP 429 instead of 502.
 */
public class QuotaUnavailableException extends InvoiceExtractionException {

    public QuotaUnavailableException(String message) {
        super(message, Kind.CONFIGURATION, null, null, null, null);
    }
}
