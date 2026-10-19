package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.service.InvoiceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP layer only: receives the request and hands the work to InvoiceService.
 * Who may call it: POST /extract is ADMIN-only (SecurityConfig); GET /{id} asks InvoiceAccessPolicy first.
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

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InvoiceResponse> extract(@RequestParam("file") MultipartFile file) {
        InvoiceResponse response = invoiceService.extractAndSave(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public InvoiceResponse getInvoice(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return invoiceService.getInvoice(id);
    }
}
