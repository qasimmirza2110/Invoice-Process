package com.actify.invoice.exception;

/**
 * Only EXTRACTED (legacy) and NEEDS_REVIEW (workflow, while the reviewer works on it) invoices can be corrected.
 * A FAILED/QUEUED/PROCESSING invoice has no data to review yet; a submitted/approved/rejected one must be sent back
 * or reopened first. Maps to HTTP 409.
 */
public class InvoiceNotEditableException extends RuntimeException {

    public InvoiceNotEditableException(Long id, Object status) {
        super("Invoice " + id + " has status " + status + " and cannot be corrected. Only EXTRACTED (legacy) or NEEDS_REVIEW invoices can be edited.");
    }
}
