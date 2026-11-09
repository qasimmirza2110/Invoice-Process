package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoice detail read used by GET /api/invoices/{id}.
 *
 * There is deliberately no direct extraction here any more: the former POST /api/invoices/extract sent a PDF to Gemini
 * outside the queue (no per-batch free-tier consent, no FIFO). Every PDF now enters through a batch upload and is
 * extracted only by the queue worker (ExtractionWorker).
 */
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceVersionWriter versionWriter;

    public InvoiceServiceImpl(InvoiceRepository invoiceRepository, InvoiceVersionWriter versionWriter) {
        this.invoiceRepository = invoiceRepository;
        this.versionWriter = versionWriter;
    }

    @Override
    @Transactional(readOnly = true) // keeps the session open so the lazy items list can be loaded
    public InvoiceResponse getInvoice(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new InvoiceNotFoundException(id));
        return InvoiceResponse.fromEntity(invoice, versionWriter.currentVersion(id));
    }
}
