package com.actify.invoice.queue;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceReviewReason;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.InvoiceReviewReasonRepository;
import com.actify.invoice.review.DuplicateInvoiceFinder;
import com.actify.invoice.validation.InvoiceExtractionValidator;
import com.actify.invoice.validation.ReviewReason;
import com.actify.invoice.validation.ReviewReasonCode;
import com.actify.invoice.validation.StorableValues;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import com.actify.invoice.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the result of one extraction attempt, in ONE short transaction after the Gemini call:
 *  - success: data (only what fits the columns), raw answer, token usage, V1, review reasons -> NEEDS_REVIEW
 *    (EVERY new invoice goes to a human reviewer, even when all automatic checks pass);
 *  - retryable failure: back to QUEUED with a later next_attempt_at;
 *  - final failure: FAILED with a safe reason. The stored PDF is always kept, so an admin can requeue.
 */
@Service
public class ExtractionOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionOutcomeService.class);

    private final InvoiceRepository invoiceRepository;
    private final ExtractionJobRepository jobRepository;
    private final InvoiceReviewReasonRepository reasonRepository;
    private final InvoiceExtractionValidator validator;
    private final InvoiceVersionWriter versionWriter;
    private final WorkflowService workflow;
    private final DuplicateInvoiceFinder duplicates;

    public ExtractionOutcomeService(InvoiceRepository invoiceRepository, ExtractionJobRepository jobRepository,
                                    InvoiceReviewReasonRepository reasonRepository, InvoiceExtractionValidator validator,
                                    InvoiceVersionWriter versionWriter, WorkflowService workflow,
                                    DuplicateInvoiceFinder duplicates) {
        this.invoiceRepository = invoiceRepository;
        this.jobRepository = jobRepository;
        this.reasonRepository = reasonRepository;
        this.validator = validator;
        this.versionWriter = versionWriter;
        this.workflow = workflow;
        this.duplicates = duplicates;
    }

    /** @return the review reasons that were stored (empty = every automatic check passed) */
    @Transactional
    public List<ReviewReason> storeForReview(Long invoiceId, LlmExtractionResponse extraction, long durationMs) {
        Invoice invoice = lockProcessing(invoiceId);
        if (invoice == null) {
            return new ArrayList<>();
        }
        ExtractionJob job = jobRepository.findById(invoiceId).orElseThrow(IllegalStateException::new);
        InvoiceExtractionResult result = extraction.getInvoice();

        applyTokenUsage(invoice, extraction.getTokenUsage(), durationMs);
        invoice.setRawLlmResponse(result.getRawResponse());
        invoice.setFailureReason(null);

        // 1. what the columns can hold; 2. business rules on the ORIGINAL values; 3. duplicates.
        List<ReviewReason> reasons = StorableValues.copyInto(result, invoice);
        reasons.addAll(0, validator.findReasons(result));
        addDuplicateReasons(invoice, reasons);

        Invoice saved = invoiceRepository.save(invoice);
        int version = versionWriter.recordExtraction(saved); // V1 = exactly what was stored
        for (ReviewReason reason : reasons) {
            reasonRepository.save(new InvoiceReviewReason(saved, version, reason.getCode(), reason.getField(), reason.getMessage()));
        }
        job.finish(LocalDateTime.now());
        workflow.transition(saved, InvoiceStatus.NEEDS_REVIEW, null, version, reasons.isEmpty()
                ? "Extracted; all automatic checks passed"
                : "Extracted; " + reasons.size() + " automatic check(s) need attention");
        log.info("Invoice {} extracted for review ({} reasons)", invoiceId, reasons.size());
        return reasons;
    }

    /** Back to the queue: eligible again at nextAttemptAt, original FIFO position kept. */
    @Transactional
    public void scheduleRetry(Long invoiceId, String errorCode, String safeMessage, LocalDateTime nextAttemptAt,
                              TokenUsage usage, long durationMs) {
        Invoice invoice = lockProcessing(invoiceId);
        if (invoice == null) {
            return;
        }
        applyTokenUsage(invoice, usage, durationMs);
        jobRepository.findById(invoiceId).orElseThrow(IllegalStateException::new)
                .scheduleRetry(nextAttemptAt, errorCode, safeMessage);
        workflow.transition(invoice, InvoiceStatus.QUEUED, null, null, errorCode + ": retry at " + nextAttemptAt);
    }

    /** Daily quota used up: back to the queue until the reset, without spending one of the job's attempts. */
    @Transactional
    public void waitForDailyReset(Long invoiceId, String errorCode, LocalDateTime resetAt, TokenUsage usage, long durationMs) {
        Invoice invoice = lockProcessing(invoiceId);
        if (invoice == null) {
            return;
        }
        applyTokenUsage(invoice, usage, durationMs);
        jobRepository.findById(invoiceId).orElseThrow(IllegalStateException::new)
                .waitForDailyReset(resetAt, errorCode, "Gemini daily quota reached; waiting for the daily reset.");
        workflow.transition(invoice, InvoiceStatus.QUEUED, null, null, errorCode + ": daily quota, waiting until " + resetAt);
    }

    @Transactional
    public void markFailed(Long invoiceId, String errorCode, String safeMessage, TokenUsage usage, long durationMs) {
        Invoice invoice = lockProcessing(invoiceId);
        if (invoice == null) {
            return;
        }
        applyTokenUsage(invoice, usage, durationMs);
        invoice.setFailureReason(safeMessage != null && safeMessage.length() > 1000 ? safeMessage.substring(0, 1000) : safeMessage);
        jobRepository.findById(invoiceId).orElseThrow(IllegalStateException::new)
                .fail(LocalDateTime.now(), errorCode, safeMessage);
        workflow.transition(invoice, InvoiceStatus.FAILED, null, null, errorCode);
        log.warn("Invoice {} FAILED ({})", invoiceId, errorCode);
    }

    /**
     * The result is only written if the invoice is still PROCESSING. If a stale-claim recovery already put it
     * back in the queue while this (slow) attempt was running, the late result is ignored.
     */
    private Invoice lockProcessing(Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.PROCESSING) {
            log.warn("Ignoring a late extraction result for invoice {} (no longer PROCESSING)", invoiceId);
            return null;
        }
        return invoice;
    }

    /** Both checks compare ONLY with the same vendor's own invoices (see DuplicateInvoiceFinder). */
    private void addDuplicateReasons(Invoice invoice, List<ReviewReason> reasons) {
        if (invoice.getOwnerUserId() == null) {
            return; // legacy single-file invoice: no vendor scope
        }
        // Normally impossible (intake rejects the same vendor's same bytes) - kept as a safety net.
        if (invoice.getSourceDocument() != null && invoiceRepository.countOtherOwnInvoicesWithSha256(
                invoice.getOwnerUserId(), invoice.getId(), invoice.getSourceDocument().getSha256()) > 0) {
            reasons.add(new ReviewReason(ReviewReasonCode.DUPLICATE_FILE, null,
                    "You already uploaded this exact PDF file for another invoice."));
        }
        Invoice earlier = duplicates.findSameVendorDuplicate(invoice, invoice.getInvoiceNumber(), invoice.getSellerTaxId(),
                invoice.getSellerName());
        if (earlier != null) {
            reasons.add(new ReviewReason(ReviewReasonCode.DUPLICATE_INVOICE_NO, "invoiceNumber",
                    DuplicateInvoiceFinder.describe(earlier)));
        }
    }

    // Same rule as the legacy flow: no usage reported -> metric columns stay null.
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
}
