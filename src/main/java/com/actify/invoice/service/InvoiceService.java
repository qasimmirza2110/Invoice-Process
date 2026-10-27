package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * The legacy single-file extraction used by POST /api/invoices/extract (ADMIN only; switched off in the local demo
 * by invoice.legacy-extract.enabled=false) and the invoice detail read used by GET /api/invoices/{id}.
 * The normal workflow does not use extractAndSave: vendors upload into batches and the queue worker extracts.
 */
public interface InvoiceService {

    /** PDF -> LLM -> validation -> PostgreSQL. Returns the stored invoice. */
    InvoiceResponse extractAndSave(MultipartFile file);

    InvoiceResponse getInvoice(Long id);
}
