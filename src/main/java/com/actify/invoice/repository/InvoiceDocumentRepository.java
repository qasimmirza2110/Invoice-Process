package com.actify.invoice.repository;

import com.actify.invoice.entity.InvoiceDocument;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Insert and read only: stored source documents are never updated or deleted by the application. */
public interface InvoiceDocumentRepository extends Repository<InvoiceDocument, String> {

    InvoiceDocument save(InvoiceDocument document);

    Optional<InvoiceDocument> findById(String id);
}
