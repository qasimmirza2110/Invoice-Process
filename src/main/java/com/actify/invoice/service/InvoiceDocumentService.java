package com.actify.invoice.service;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves a stored original PDF by its generated document id, after an integrity check. */
@Service
public class InvoiceDocumentService {

    private final InvoiceDocumentRepository documentRepository;
    private final InvoiceDocumentStorage documentStorage;
    private final InvoiceRepository invoiceRepository;

    public InvoiceDocumentService(InvoiceDocumentRepository documentRepository, InvoiceDocumentStorage documentStorage,
                                  InvoiceRepository invoiceRepository) {
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
        this.invoiceRepository = invoiceRepository;
    }

    /**
     * The original PDF of THIS invoice (invoices.source_document_id). The same file backs every version of the invoice:
     * corrections change invoice_versions, never the stored PDF. 404 when the invoice has no stored original
     * (old legacy rows) - the caller has already checked read access to the invoice.
     */
    @Transactional(readOnly = true)
    public DocumentContent loadForInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (invoice.getSourceDocument() == null) {
            throw new DocumentNotFoundException();
        }
        return load(invoice.getSourceDocument().getId());
    }

    @Transactional(readOnly = true)
    public DocumentContent load(String documentId) {
        InvoiceDocument document = documentRepository.findById(documentId)
                .orElseThrow(DocumentNotFoundException::new);
        byte[] bytes = documentStorage.read(document.getId(), document.getSha256());
        return new DocumentContent(document, bytes);
    }

    public static class DocumentContent {
        private final InvoiceDocument document;
        private final byte[] bytes;

        DocumentContent(InvoiceDocument document, byte[] bytes) {
            this.document = document;
            this.bytes = bytes;
        }

        public InvoiceDocument getDocument() { return document; }
        public byte[] getBytes() { return bytes; }
    }
}
