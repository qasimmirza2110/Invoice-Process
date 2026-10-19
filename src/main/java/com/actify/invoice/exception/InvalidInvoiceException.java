package com.actify.invoice.exception;

/** The uploaded file itself is unacceptable (empty, not a PDF, too large...). Maps to HTTP 400. */
public class InvalidInvoiceException extends RuntimeException {

    public InvalidInvoiceException(String message) {
        super(message);
    }
}
