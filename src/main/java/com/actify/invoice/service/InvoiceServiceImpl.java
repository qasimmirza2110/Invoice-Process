package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.entity.InvoiceItem;
import com.actify.invoice.entity.GeminiCallAttempt;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.exception.QuotaUnavailableException;
import com.actify.invoice.quota.FailureClassifier;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.storage.StoredDocument;
import com.actify.invoice.validation.InvoiceExtractionValidator;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * Coordinates the invoice workflow. It knows WHAT happens and in which order,
 * but not HOW Gemini is called (LlmInvoiceExtractor) or what makes an invoice valid
 * (InvoiceExtractionValidator).
 *
 * Note: extractAndSave() is intentionally NOT @Transactional as a whole:
 *  - the Gemini call can take many seconds, and we don't want a DB transaction open that long;
 *  - when extraction fails we still want the FAILED row to be committed for traceability.
 * The important "all or nothing" part - document metadata + invoice fields + line items + V1 -
 * runs in one short TransactionTemplate transaction after the Gemini call (persistExtracted).
 */
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceServiceImpl.class);

    private final InvoiceRepository invoiceRepository;
    private final LlmInvoiceExtractor llmInvoiceExtractor;
    private final InvoiceExtractionValidator validator;
    private final InvoiceDocumentStorage documentStorage;
    private final InvoiceDocumentRepository documentRepository;
    private final InvoiceVersionWriter versionWriter;
    private final TransactionTemplate transactionTemplate;
    private final GeminiQuotaGuard quotaGuard;

    public InvoiceServiceImpl(InvoiceRepository invoiceRepository,
                              LlmInvoiceExtractor llmInvoiceExtractor,
                              InvoiceExtractionValidator validator,
                              InvoiceDocumentStorage documentStorage,
                              InvoiceDocumentRepository documentRepository,
                              InvoiceVersionWriter versionWriter,
                              TransactionTemplate transactionTemplate,
                              GeminiQuotaGuard quotaGuard) {
        this.invoiceRepository = invoiceRepository;
        this.llmInvoiceExtractor = llmInvoiceExtractor;
        this.validator = validator;
        this.documentStorage = documentStorage;
        this.documentRepository = documentRepository;
        this.versionWriter = versionWriter;
        this.transactionTemplate = transactionTemplate;
        this.quotaGuard = quotaGuard;
    }

    @Override
    public InvoiceResponse extractAndSave(MultipartFile file) {
        byte[] pdfBytes = PdfFiles.validateAndRead(file);

        // Save a PROCESSING row first, so every upload is traceable - even ones that fail later.
        Invoice invoice = new Invoice();
        invoice.setSourceFileName(PdfFiles.safeFileName(file.getOriginalFilename()));
        invoice.setSourceFileSize(file.getSize());
        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice = invoiceRepository.save(invoice);
        log.info("Invoice {} created, starting extraction", invoice.getId());

        try {
            // The same shared quota guard as the queue worker: checked and recorded BEFORE the call.
            Long attemptId = reserveGeminiCall(invoice);

            // One Gemini call for the whole PDF, all pages together.
            long startNanos = System.nanoTime();
            LlmExtractionResponse extraction;
            try {
                extraction = llmInvoiceExtractor.extract(pdfBytes);
            } catch (InvoiceExtractionException e) {
                quotaGuard.complete(attemptId, outcomeOf(e), e.getHttpStatus(), FailureClassifier.errorCode(e),
                        e.getTokenUsage(), elapsedMillis(startNanos));
                // If Gemini answered but the answer was unusable, the tokens were still used: keep them.
                applyTokenUsage(invoice, e.getTokenUsage(), elapsedMillis(startNanos));
                throw e;
            }
            quotaGuard.complete(attemptId, GeminiCallAttempt.Outcome.SUCCESS, 200, null, extraction.getTokenUsage(),
                    elapsedMillis(startNanos));
            applyTokenUsage(invoice, extraction.getTokenUsage(), elapsedMillis(startNanos));

            InvoiceExtractionResult result = extraction.getInvoice();
            invoice.setRawLlmResponse(result.getRawResponse());

            // Nothing from the LLM reaches the normalized columns unless it passes validation.
            validator.validate(result);

            copyExtractedData(result, invoice);
            invoice.setStatus(InvoiceStatus.EXTRACTED);

            return persistExtracted(invoice, pdfBytes);

        } catch (InvoiceExtractionException | InvoiceValidationException e) {
            markFailed(invoice, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            markFailed(invoice, "Unexpected error during processing");
            throw e;
        }
    }

    /**
     * Asks the shared quota guard for one Gemini call and records the attempt (IN_FLIGHT) in one short transaction.
     * Without configured limits the legacy endpoint keeps working as before (no local limit), but every call is
     * still recorded. When the local budget is used up the call is not made: QuotaUnavailableException (HTTP 429).
     */
    private Long reserveGeminiCall(Invoice invoice) {
        return transactionTemplate.execute(status -> {
            GeminiQuotaGuard.Check check = quotaGuard.check(false);
            if (!check.isAllowed()) {
                throw new QuotaUnavailableException(check.getReason());
            }
            GeminiCallAttempt attempt = quotaGuard.recordStart(invoice, GeminiCallAttempt.Caller.LEGACY_EXTRACT,
                    check.getEstimatedInputTokens());
            return attempt == null ? null : attempt.getId();
        });
    }

    private static GeminiCallAttempt.Outcome outcomeOf(InvoiceExtractionException e) {
        switch (FailureClassifier.classify(e)) {
            case RETRY:
                return GeminiCallAttempt.Outcome.RETRYABLE_ERROR;
            case WAIT_FOR_DAILY_RESET:
                return GeminiCallAttempt.Outcome.DAILY_QUOTA;
            default:
                return GeminiCallAttempt.Outcome.PERMANENT_ERROR;
        }
    }

    /**
     * Stores the original PDF, then saves document metadata + invoice + items + V1 in ONE database
     * transaction: either all of them are committed or none. The file is written first (a file write
     * can't join the transaction) and deleted again if the transaction fails.
     */
    private InvoiceResponse persistExtracted(Invoice invoice, byte[] pdfBytes) {
        StoredDocument stored = documentStorage.store(pdfBytes);
        try {
            return transactionTemplate.execute(status -> {
                InvoiceDocument document = documentRepository.save(new InvoiceDocument(stored.getId(),
                        stored.getSha256(), stored.getSizeBytes(), PdfFiles.PDF_CONTENT_TYPE, invoice.getSourceFileName()));
                invoice.setSourceDocument(document);

                // Invoice fields and line items are saved together (cascade) ...
                Invoice saved = invoiceRepository.save(invoice);
                // ... and V1 records exactly what was stored, in the same transaction.
                int version = versionWriter.recordExtraction(saved);
                log.info("Invoice {} extracted with {} line items, stored as version {}",
                        saved.getId(), saved.getItems().size(), version);
                return InvoiceResponse.fromEntity(saved, version);
            });
        } catch (RuntimeException e) {
            // Nothing was committed, so the stored file would be an orphan.
            invoice.setSourceDocument(null);
            documentStorage.deleteQuietly(stored.getId());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true) // keeps the session open so the lazy items list can be loaded
    public InvoiceResponse getInvoice(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new InvoiceNotFoundException(id));
        return InvoiceResponse.fromEntity(invoice, versionWriter.currentVersion(id));
    }

    private void copyExtractedData(InvoiceExtractionResult result, Invoice invoice) {
        invoice.setInvoiceNumber(trim(result.getInvoiceNumber()));
        // Safe to parse here: the validator already rejected badly formatted dates.
        invoice.setInvoiceDate(result.getInvoiceDate() == null ? null : LocalDate.parse(result.getInvoiceDate()));
        invoice.setSellerName(trim(result.getSellerName()));
        invoice.setSellerTaxId(trim(result.getSellerTaxId()));
        invoice.setBuyerName(trim(result.getBuyerName()));
        invoice.setBuyerTaxId(trim(result.getBuyerTaxId()));
        invoice.setCurrency(result.getCurrency());
        invoice.setSubtotal(result.getSubtotal());
        invoice.setDiscountAmount(result.getDiscountAmount());
        invoice.setShippingAmount(result.getShippingAmount());
        invoice.setTaxAmount(result.getTaxAmount());
        invoice.setTotalAmount(result.getTotalAmount());

        for (InvoiceItemDto dto : result.getItems()) {
            InvoiceItem item = new InvoiceItem();
            item.setDescription(trim(dto.getDescription()));
            item.setQuantity(dto.getQuantity());
            item.setUnitPrice(dto.getUnitPrice());
            item.setTaxRate(dto.getTaxRate());
            item.setLineTotal(dto.getLineTotal());
            invoice.addItem(item);
        }
    }

    /**
     * Copies the reported usage onto the invoice. No usage (e.g. Gemini was never reached)
     * leaves all metric columns null - we don't store a duration for a call that produced no answer.
     */
    private void applyTokenUsage(Invoice invoice, TokenUsage usage, long durationMs) {
        if (usage == null) {
            return;
        }
        invoice.setGeminiModelId(usage.getModelId());
        invoice.setGeminiModelVersion(usage.getModelVersion());
        invoice.setPromptTokenCount(usage.getPromptTokenCount());
        invoice.setCandidatesTokenCount(usage.getCandidatesTokenCount());
        invoice.setThoughtsTokenCount(usage.getThoughtsTokenCount());
        invoice.setTotalTokenCount(usage.getTotalTokenCount());
        invoice.setExtractionDurationMs(durationMs);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void markFailed(Invoice invoice, String reason) {
        try {
            // Never keep half-built items on a failed invoice.
            invoice.getItems().clear();
            invoice.setStatus(InvoiceStatus.FAILED);
            invoice.setFailureReason(reason != null && reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            invoiceRepository.save(invoice);
            log.warn("Invoice {} marked FAILED: {}", invoice.getId(), reason);
        } catch (RuntimeException saveError) {
            // Don't hide the original error just because the status update failed too.
            log.error("Could not mark invoice {} as FAILED", invoice.getId(), saveError);
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
