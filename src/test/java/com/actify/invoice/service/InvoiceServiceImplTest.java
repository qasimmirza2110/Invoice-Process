package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The invoice detail read (InvoiceServiceImpl). There is no direct extraction any more: PDFs are extracted only by the
 * queue worker (ExtractionWorker, covered by its own tests and the PostgreSQL integration tests).
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceVersionWriter versionWriter;

    private InvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(invoiceRepository, versionWriter);
    }

    @Test
    void getReturnsExactlyThePersistedTokenUsage() {
        Invoice invoice = new Invoice();
        invoice.setId(1L);
        invoice.setStatus(InvoiceStatus.NEEDS_REVIEW);
        invoice.setGeminiModelId("gemini-3.5-flash-lite");
        invoice.setPromptTokenCount(1290L);
        invoice.setCandidatesTokenCount(180L);
        invoice.setTotalTokenCount(1470L);
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        TokenUsage usage = service.getInvoice(1L).getTokenUsage();

        assertThat(usage.getModelId()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(usage.getPromptTokenCount()).isEqualTo(1290L);
        assertThat(usage.getCandidatesTokenCount()).isEqualTo(180L);
        assertThat(usage.getTotalTokenCount()).isEqualTo(1470L);
    }

    @Test
    void historicalInvoiceWithoutMetricsReturnsNullTokenFields() {
        Invoice old = new Invoice();
        old.setId(1L);
        old.setStatus(InvoiceStatus.EXTRACTED);
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(old));

        TokenUsage usage = service.getInvoice(1L).getTokenUsage();

        assertThat(usage).isNotNull();
        assertThat(usage).hasAllNullFieldsOrProperties();
    }

    @Test
    void getReturnsTheCurrentVersionNumber() {
        Invoice invoice = new Invoice();
        invoice.setId(9L);
        invoice.setStatus(InvoiceStatus.EXTRACTED);
        when(invoiceRepository.findById(9L)).thenReturn(Optional.of(invoice));
        when(versionWriter.currentVersion(9L)).thenReturn(3);

        InvoiceResponse response = service.getInvoice(9L);

        assertThat(response.getCurrentVersion()).isEqualTo(3);
        assertThat(response.getSourceDocument()).isNull();   // invoices from before PDF storage have none
    }

    @Test
    void anUnknownInvoiceIsNotFound() {
        when(invoiceRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getInvoice(404L)).isInstanceOf(InvoiceNotFoundException.class);
    }
}
