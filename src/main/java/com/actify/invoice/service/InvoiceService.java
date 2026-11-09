package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceResponse;

/**
 * The invoice detail read used by GET /api/invoices/{id}. Extraction happens only in the queue worker: vendors upload
 * PDFs into batches (POST /api/batches/{id}/files) and ExtractionWorker processes them.
 */
public interface InvoiceService {

    InvoiceResponse getInvoice(Long id);
}
