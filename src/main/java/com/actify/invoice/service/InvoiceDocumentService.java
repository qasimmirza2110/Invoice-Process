package com.actify.invoice.service;

import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves a stored original PDF by its generated document id, after an integrity check. */
@Service
public class InvoiceDocumentService {

    private final InvoiceDocumentRepository documentRepository;
    private final InvoiceDocumentStorage documentStorage;

    public InvoiceDocumentService(InvoiceDocumentRepository documentRepository, InvoiceDocumentStorage documentStorage) {
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
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
