package com.actify.invoice.controller;

import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.service.InvoiceDocumentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The only way to read a stored original PDF. Files live in the private invoice.storage.dir folder, so there is no
 * direct URL and no directory listing. Access is decided through the invoice that owns the document
 * (InvoiceAccessPolicy), never by knowing the random document id alone.
 */
@RestController
public class DocumentController {

    private final InvoiceDocumentService documentService;
    private final InvoiceAccessPolicy accessPolicy;

    public DocumentController(InvoiceDocumentService documentService, InvoiceAccessPolicy accessPolicy) {
        this.documentService = documentService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Preferred: the original PDF of one invoice, addressed by the invoice id (the caller never needs the storage id).
     * Same access rule as GET /api/invoices/{id}: owner vendor, assigned reviewer, admin - anyone else gets 404.
     */
    @GetMapping("/api/invoices/{invoiceId}/document")
    public ResponseEntity<byte[]> getInvoiceDocument(@PathVariable Long invoiceId) {
        accessPolicy.requireReadableInvoice(invoiceId);
        return pdf(documentService.loadForInvoice(invoiceId));
    }

    /** Kept for existing clients (the sourceDocument.url field). */
    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<byte[]> getDocument(@PathVariable String documentId) {
        accessPolicy.requireReadableDocument(documentId);
        return pdf(documentService.load(documentId));
    }

    private static ResponseEntity<byte[]> pdf(InvoiceDocumentService.DocumentContent content) {
        String fileName = content.getDocument().getOriginalFileName() == null
                ? "invoice.pdf" : content.getDocument().getOriginalFileName();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(content.getBytes());
    }
}
