package com.actify.invoice.dto;

/**
 * Result of PUT /api/invoices/{id}. changed=false means the submitted data was identical to the
 * current version, so no new version was created and invoice.currentVersion is unchanged.
 */
public class InvoiceSaveResponse {

    private final boolean changed;
    private final InvoiceResponse invoice;

    public InvoiceSaveResponse(boolean changed, InvoiceResponse invoice) {
        this.changed = changed;
        this.invoice = invoice;
    }

    public boolean isChanged() { return changed; }
    public InvoiceResponse getInvoice() { return invoice; }
}
