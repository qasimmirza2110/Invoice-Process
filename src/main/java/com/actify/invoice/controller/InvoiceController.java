package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.service.InvoiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer only: GET /{id} asks InvoiceAccessPolicy first, then InvoiceService.
 * There is no direct extraction endpoint: PDFs enter through batch uploads and only the queue worker calls Gemini.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceAccessPolicy accessPolicy;

    public InvoiceController(InvoiceService invoiceService, InvoiceAccessPolicy accessPolicy) {
        this.invoiceService = invoiceService;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/{id}")
    public InvoiceResponse getInvoice(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return invoiceService.getInvoice(id);
    }
}
