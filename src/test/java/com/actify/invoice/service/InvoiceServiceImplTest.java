package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvalidInvoiceException;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.storage.StoredDocument;
import com.actify.invoice.validation.InvoiceExtractionValidator;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private LlmInvoiceExtractor llmInvoiceExtractor;

    @Mock
    private InvoiceDocumentStorage documentStorage;

    @Mock
    private InvoiceDocumentRepository documentRepository;

    @Mock
    private InvoiceVersionWriter versionWriter;

    @Mock
    private GeminiQuotaGuard quotaGuard;

    private InvoiceServiceImpl service;

    private final MockMultipartFile pdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf",
            "%PDF-1.4 synthetic invoice".getBytes(StandardCharsets.US_ASCII));

    @BeforeEach
    void setUp() {
        // Real validator: we want the service test to go through the actual business rules.
        // Storage, document metadata and versioning are mocked here (no disk, no DB); they are covered
        // by InvoiceDocumentStorageTest and the PostgreSQL integration tests. The transaction manager is a
        // mock, so the TransactionTemplate simply runs the callback.
        service = new InvoiceServiceImpl(invoiceRepository, llmInvoiceExtractor, new InvoiceExtractionValidator(),
                documentStorage, documentRepository, versionWriter,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), quotaGuard);
        // Phase 3: the shared quota guard always allows the call here (its own rules are tested in GeminiQuotaGuardTest).
        lenient().when(quotaGuard.check(false)).thenReturn(GeminiQuotaGuard.Check.allowed(1000));
        lenient().when(documentStorage.store(any(byte[].class)))
                .thenReturn(new StoredDocument("11111111-2222-4333-8444-555555555555", "ab", 26));
    }

    private void repositoryAssignsIdOnSave() {
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(1L);
            }
            return invoice;
        });
    }

    private Invoice lastSaved() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository, atLeastOnce()).save(captor.capture());
        List<Invoice> saves = captor.getAllValues();
        return saves.get(saves.size() - 1);
    }

    private InvoiceExtractionResult extractedInvoice() {
        InvoiceExtractionResult result = new InvoiceExtractionResult();
        result.setInvoiceNumber("INV-1001");
        result.setInvoiceDate("2026-10-06");
        result.setSellerName("ABC Technologies Pvt Ltd");
        result.setBuyerName("XYZ Solutions");
        result.setCurrency("INR");
        result.setSubtotal(new BigDecimal("10000.00"));
        result.setTaxAmount(new BigDecimal("1800.00"));
        result.setTotalAmount(new BigDecimal("11800.00"));
        result.setItems(Collections.singletonList(new InvoiceItemDto("Laptop Stand", new BigDecimal("2"),
                new BigDecimal("5000.00"), new BigDecimal("18"), new BigDecimal("10000.00"))));
        result.setRawResponse("{\"invoiceNumber\":\"INV-1001\"}");
        return result;
    }

    /** What the synthetic 2-page fixture should give: Laptop Stand (page 1) + USB-C Cable (page 2 image only). */
    private InvoiceExtractionResult twoPageInvoice() {
        InvoiceExtractionResult result = new InvoiceExtractionResult();
        result.setInvoiceNumber("INV-2026-0042");
        result.setInvoiceDate("2026-10-06");
        result.setSellerName("Demo Tech Supplies Pvt Ltd");
        result.setBuyerName("Sample Retail Solutions LLP");
        result.setCurrency("INR");
        result.setSubtotal(new BigDecimal("10500.00"));
        result.setTaxAmount(new BigDecimal("1890.00"));
        result.setTotalAmount(new BigDecimal("12390.00"));
        result.setItems(Arrays.asList(
                new InvoiceItemDto("Laptop Stand", new BigDecimal("2"), new BigDecimal("5000.00"),
                        new BigDecimal("18"), new BigDecimal("10000.00")),
                new InvoiceItemDto("USB-C Cable", new BigDecimal("1"), new BigDecimal("500.00"),
                        new BigDecimal("18"), new BigDecimal("500.00"))));
        result.setRawResponse("{\"invoiceNumber\":\"INV-2026-0042\"}");
        return result;
    }

    private TokenUsage usage() {
        return new TokenUsage("gemini-3.5-flash-lite", "gemini-3.5-flash-lite-test-version",
                1290L, 180L, null, 1470L, null);
    }

    @Test
    void persistsInvoiceAndItems() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(extractedInvoice(), null));

        InvoiceResponse response = service.extractAndSave(pdf);

        Invoice saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);
        assertThat(saved.getInvoiceNumber()).isEqualTo("INV-1001");
        assertThat(saved.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("11800.00");
        assertThat(saved.getSourceFileName()).isEqualTo("invoice.pdf");
        assertThat(saved.getRawLlmResponse()).contains("INV-1001");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getInvoice()).isSameAs(saved);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);
        assertThat(response.getItems()).hasSize(1);
    }

    @Test
    void persistsEveryPageItemAndTheTokenUsage() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(twoPageInvoice(), usage()));

        InvoiceResponse response = service.extractAndSave(pdf);

        Invoice saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);
        // One invoice header, both line items - including the one that only exists on page 2.
        assertThat(saved.getItems()).extracting("description").containsExactly("Laptop Stand", "USB-C Cable");
        assertThat(saved.getItems().get(1).getLineTotal()).isEqualByComparingTo("500.00");
        assertThat(saved.getItems()).allSatisfy(item -> assertThat(item.getInvoice()).isSameAs(saved));

        assertThat(saved.getGeminiModelId()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(saved.getGeminiModelVersion()).isEqualTo("gemini-3.5-flash-lite-test-version");
        assertThat(saved.getPromptTokenCount()).isEqualTo(1290L);
        assertThat(saved.getCandidatesTokenCount()).isEqualTo(180L);
        assertThat(saved.getThoughtsTokenCount()).isNull();
        assertThat(saved.getTotalTokenCount()).isEqualTo(1470L);
        assertThat(saved.getExtractionDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getTokenUsage().getTotalTokenCount()).isEqualTo(1470L);
    }

    @Test
    void getReturnsExactlyThePersistedTokenUsage() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(twoPageInvoice(), usage()));
        InvoiceResponse postResponse = service.extractAndSave(pdf);
        Invoice persisted = lastSaved();
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(persisted));

        InvoiceResponse getResponse = service.getInvoice(1L);

        assertThat(getResponse.getTokenUsage()).usingRecursiveComparison().isEqualTo(postResponse.getTokenUsage());
        assertThat(getResponse.getItems()).hasSize(2);
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
    void missingPage2ItemIsRejectedByTheSubtotalCheck() {
        repositoryAssignsIdOnSave();
        InvoiceExtractionResult onlyPage1 = twoPageInvoice();
        onlyPage1.setItems(Collections.singletonList(onlyPage1.getItems().get(0)));   // USB-C Cable dropped
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(onlyPage1, usage()));

        assertThatThrownBy(() -> service.extractAndSave(pdf))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("sum of line totals (10000.00) does not match subtotal (10500.00)");

        Invoice last = lastSaved();
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getItems()).isEmpty();
    }

    @Test
    void validationFailureKeepsTheTokenUsage() {
        repositoryAssignsIdOnSave();
        InvoiceExtractionResult wrongTotals = twoPageInvoice();
        wrongTotals.setTotalAmount(new BigDecimal("99999.00"));
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(wrongTotals, usage()));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(InvoiceValidationException.class);

        Invoice last = lastSaved();
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getTotalAmount()).isNull();
        assertThat(last.getPromptTokenCount()).isEqualTo(1290L);   // the tokens were used, so keep them
        assertThat(last.getTotalTokenCount()).isEqualTo(1470L);
        assertThat(last.getExtractionDurationMs()).isNotNull();
    }

    @Test
    void unparseableAnswerKeepsTheTokenUsageFromTheException() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenThrow(new InvoiceExtractionException(
                "Gemini answer does not match the expected invoice JSON format", usage(), null));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(InvoiceExtractionException.class);

        Invoice last = lastSaved();
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getTotalTokenCount()).isEqualTo(1470L);
        assertThat(last.getGeminiModelVersion()).isEqualTo("gemini-3.5-flash-lite-test-version");
    }

    @Test
    void marksInvoiceFailedWhenGeminiFails() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenThrow(new InvoiceExtractionException("Gemini request failed with HTTP 500"));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(InvoiceExtractionException.class);

        Invoice last = lastSaved();
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getFailureReason()).contains("HTTP 500");
        assertThat(last.getItems()).isEmpty();
        // No answer from Gemini -> nothing reported -> metrics stay null (no invented zeros).
        assertThat(last.getTotalTokenCount()).isNull();
        assertThat(last.getExtractionDurationMs()).isNull();
    }

    /** Sample layout A: 220.00 - 18.20 discount + 25.00 shipping + 16.65 tax = 243.45. */
    private InvoiceExtractionResult shippingAndDiscountInvoice() {
        InvoiceExtractionResult result = new InvoiceExtractionResult();
        result.setInvoiceNumber("#000000005");
        result.setSellerName("ForceStore");
        result.setBuyerName("Veronica Costello");
        result.setCurrency("USD");
        result.setSubtotal(new BigDecimal("220.00"));
        result.setDiscountAmount(new BigDecimal("18.20"));
        result.setShippingAmount(new BigDecimal("25.00"));
        result.setTaxAmount(new BigDecimal("16.65"));
        result.setTotalAmount(new BigDecimal("243.45"));
        result.setItems(Arrays.asList(
                new InvoiceItemDto("Desk Lamp 7\" Shade", new BigDecimal("3"), null, null, new BigDecimal("129.00")),
                new InvoiceItemDto("Canvas Bag", new BigDecimal("1"), null, null, new BigDecimal("59.00")),
                new InvoiceItemDto("Cotton T-Shirt", new BigDecimal("1"), null, null, new BigDecimal("32.00"))));
        result.setRawResponse("{\"invoiceNumber\":\"#000000005\"}");
        return result;
    }

    @Test
    void persistsShippingDiscountAndProductLinesOnly() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(shippingAndDiscountInvoice(), usage()));

        InvoiceResponse response = service.extractAndSave(pdf);

        Invoice saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);
        assertThat(saved.getSubtotal()).isEqualByComparingTo("220.00");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("18.20");
        assertThat(saved.getShippingAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getTaxAmount()).isEqualByComparingTo("16.65");
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("243.45");
        assertThat(saved.getItems()).extracting("description")
                .containsExactly("Desk Lamp 7\" Shade", "Canvas Bag", "Cotton T-Shirt");

        assertThat(response.getDiscountAmount()).isEqualByComparingTo("18.20");
        assertThat(response.getShippingAmount()).isEqualByComparingTo("25.00");
        assertThat(response.getItems()).hasSize(3);
    }

    @Test
    void invoiceWithoutAdjustmentsStoresNullShippingAndDiscount() {
        repositoryAssignsIdOnSave();
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(extractedInvoice(), null));

        InvoiceResponse response = service.extractAndSave(pdf);

        assertThat(lastSaved().getShippingAmount()).isNull();
        assertThat(lastSaved().getDiscountAmount()).isNull();
        assertThat(response.getShippingAmount()).isNull();
        assertThat(response.getDiscountAmount()).isNull();
    }

    @Test
    void wrongTotalWithAdjustmentsIsNeverStoredAsExtracted() {
        repositoryAssignsIdOnSave();
        InvoiceExtractionResult wrong = shippingAndDiscountInvoice();
        wrong.setTotalAmount(new BigDecimal("279.85"));
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(wrong, usage()));

        assertThatThrownBy(() -> service.extractAndSave(pdf))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("does not match totalAmount (279.85)");

        Invoice last = lastSaved();
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getShippingAmount()).isNull();
        assertThat(last.getDiscountAmount()).isNull();
        assertThat(last.getTotalAmount()).isNull();
        assertThat(last.getItems()).isEmpty();
    }

    @Test
    void invalidLlmDataIsNeverStoredAsExtracted() {
        repositoryAssignsIdOnSave();
        InvoiceExtractionResult wrongTotals = extractedInvoice();
        wrongTotals.setTotalAmount(new BigDecimal("99999.00"));
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(wrongTotals, null));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(InvoiceValidationException.class);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository, atLeastOnce()).save(captor.capture());
        for (Invoice saved : captor.getAllValues()) {
            assertThat(saved.getStatus()).isNotEqualTo(InvoiceStatus.EXTRACTED);
        }
        Invoice last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(last.getTotalAmount()).isNull();          // bad values never reach the normalized columns
        assertThat(last.getItems()).isEmpty();
        assertThat(last.getRawLlmResponse()).isNotNull();    // but the raw answer is kept for debugging
    }

    @Test
    void rejectsNonPdfBeforeCallingGemini() {
        MockMultipartFile notPdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                "just some text".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> service.extractAndSave(notPdf))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("not a valid PDF");
        verifyNoInteractions(llmInvoiceExtractor, invoiceRepository);
    }

    @Test
    void rejectsEmptyWrongExtensionAndOversizedFilesBeforeCallingGemini() {
        MockMultipartFile empty = new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[0]);
        MockMultipartFile txt = new MockMultipartFile("file", "invoice.txt", "text/plain",
                "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 5);
        MockMultipartFile oversized = new MockMultipartFile("file", "invoice.pdf", "application/pdf", big);

        assertThatThrownBy(() -> service.extractAndSave(empty)).isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> service.extractAndSave(txt)).isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining(".pdf");
        assertThatThrownBy(() -> service.extractAndSave(oversized)).isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("10 MB");
        verifyNoInteractions(llmInvoiceExtractor, invoiceRepository);
        verifyNoInteractions(documentStorage, documentRepository, versionWriter);
    }

    // ---------------- original PDF storage + V1 ----------------

    @Test
    void successfulExtractionStoresTheExactPdfLinksItAndRecordsV1() {
        repositoryAssignsIdOnSave();
        when(documentRepository.save(any(InvoiceDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionWriter.recordExtraction(any(Invoice.class))).thenReturn(1);
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(extractedInvoice(), null));

        InvoiceResponse response = service.extractAndSave(pdf);

        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(documentStorage).store(storedBytes.capture());
        assertThat(storedBytes.getValue()).isEqualTo("%PDF-1.4 synthetic invoice".getBytes(StandardCharsets.US_ASCII));
        Invoice saved = lastSaved();
        assertThat(saved.getSourceDocument().getId()).isEqualTo("11111111-2222-4333-8444-555555555555");
        assertThat(saved.getSourceDocument().getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSourceDocument().getOriginalFileName()).isEqualTo("invoice.pdf");
        verify(versionWriter).recordExtraction(saved);
        assertThat(response.getCurrentVersion()).isEqualTo(1);
        assertThat(response.getSourceDocument().getUrl()).isEqualTo("/api/documents/11111111-2222-4333-8444-555555555555");
        verify(documentStorage, never()).deleteQuietly(any());
    }

    @Test
    void failedDatabaseStepDeletesTheStoredPdfAndMarksTheInvoiceFailed() {
        repositoryAssignsIdOnSave();
        when(versionWriter.recordExtraction(any(Invoice.class))).thenThrow(new IllegalStateException("db down"));
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(extractedInvoice(), null));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(IllegalStateException.class);

        verify(documentStorage).deleteQuietly("11111111-2222-4333-8444-555555555555");
        Invoice failed = lastSaved();
        assertThat(failed.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(failed.getSourceDocument()).isNull();
        assertThat(failed.getItems()).isEmpty();
    }

    @Test
    void invalidExtractionStoresNoPdf() {
        repositoryAssignsIdOnSave();
        InvoiceExtractionResult wrong = extractedInvoice();
        wrong.setTotalAmount(new BigDecimal("99999.00"));
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenReturn(new LlmExtractionResponse(wrong, null));

        assertThatThrownBy(() -> service.extractAndSave(pdf)).isInstanceOf(InvoiceValidationException.class);

        verifyNoInteractions(documentStorage, documentRepository, versionWriter);
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
}
