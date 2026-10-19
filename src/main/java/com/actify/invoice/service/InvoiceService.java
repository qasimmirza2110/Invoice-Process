package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceResponse;
import org.springframework.web.multipart.MultipartFile;

public interface InvoiceService {

    /** PDF -> LLM -> validation -> PostgreSQL. Returns the stored invoice. */
    InvoiceResponse extractAndSave(MultipartFile file);

    InvoiceResponse getInvoice(Long id);
}
