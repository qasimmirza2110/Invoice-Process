package com.actify.invoice.exception;

/** Maps to HTTP 404. */
public class VersionNotFoundException extends RuntimeException {

    public VersionNotFoundException(Long invoiceId, int versionNumber) {
        super("Invoice " + invoiceId + " has no version " + versionNumber);
    }
}
