package com.actify.invoice.exception;

/** Maps to HTTP 404. */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(Long id) {
        super("Invoice not found with id " + id);
    }
}
