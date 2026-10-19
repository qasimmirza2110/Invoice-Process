package com.actify.invoice.review;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.VersionConflictException;
import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import com.actify.invoice.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;

/**
 * The admin's decisions (ADMIN-only URLs). Each one locks the invoice row, checks the status through
 * WorkflowService (409 when not allowed) and writes one append-only workflow event with the admin, the version
 * and the reason. Earlier decisions are never edited: a reopened invoice keeps its old APPROVED event in history.
 *
 * Approval is tied to ONE exact version: the version the admin saw must equal the version the reviewer submitted
 * AND the invoice's current version (409 otherwise). An APPROVED invoice means "a person approved this saved
 * version" - not "Gemini was accurate".
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    static final int MAX_REASON_LENGTH = 500;

    private final InvoiceRepository invoiceRepository;
    private final ExtractionJobRepository jobRepository;
    private final InvoiceVersionWriter versionWriter;
    private final WorkflowService workflow;
    private final CurrentUserService currentUser;

    public ApprovalService(InvoiceRepository invoiceRepository, ExtractionJobRepository jobRepository,
                           InvoiceVersionWriter versionWriter, WorkflowService workflow, CurrentUserService currentUser) {
        this.invoiceRepository = invoiceRepository;
        this.jobRepository = jobRepository;
        this.versionWriter = versionWriter;
        this.workflow = workflow;
        this.currentUser = currentUser;
    }

    @Transactional
    public InvoiceResponse approve(Long invoiceId, Integer version, String note) {
        Invoice invoice = lock(invoiceId);
        int current = requireSubmittedVersion(invoice, version);
        workflow.transition(invoice, InvoiceStatus.APPROVED, actor(), version, optional(note, "Approved"));
        log.info("Invoice {} version {} APPROVED by user {}", invoiceId, version, actor());
        return InvoiceResponse.fromEntity(invoice, current);
    }

    @Transactional
    public InvoiceResponse reject(Long invoiceId, Integer version, String reason) {
        String why = requireReason(reason);
        Invoice invoice = lock(invoiceId);
        int current = requireSubmittedVersion(invoice, version);
        workflow.transition(invoice, InvoiceStatus.REJECTED, actor(), version, why);
        return InvoiceResponse.fromEntity(invoice, current);
    }

    /** PENDING_APPROVAL -> NEEDS_REVIEW: the reviewer must look again; the submission is cleared. */
    @Transactional
    public InvoiceResponse sendBack(Long invoiceId, String reason) {
        String why = requireReason(reason);
        Invoice invoice = lock(invoiceId);
        if (invoice.getStatus() != InvoiceStatus.PENDING_APPROVAL) {
            throw new WorkflowConflictException("Only an invoice waiting for approval can be sent back (it is " + invoice.getStatus() + ").");
        }
        workflow.transition(invoice, InvoiceStatus.NEEDS_REVIEW, actor(), invoice.getSubmittedVersion(), why);
        invoice.setSubmittedVersion(null);
        return InvoiceResponse.fromEntity(invoice, versionWriter.currentVersion(invoiceId));
    }

    /** APPROVED / REJECTED -> NEEDS_REVIEW. The earlier decision stays in the event history. */
    @Transactional
    public InvoiceResponse reopen(Long invoiceId, String reason) {
        String why = requireReason(reason);
        Invoice invoice = lock(invoiceId);
        if (invoice.getStatus() != InvoiceStatus.APPROVED && invoice.getStatus() != InvoiceStatus.REJECTED) {
            throw new WorkflowConflictException("Only an approved or rejected invoice can be reopened (it is " + invoice.getStatus() + ").");
        }
        workflow.transition(invoice, InvoiceStatus.NEEDS_REVIEW, actor(), invoice.getSubmittedVersion(), why);
        invoice.setSubmittedVersion(null);
        return InvoiceResponse.fromEntity(invoice, versionWriter.currentVersion(invoiceId));
    }

    /** FAILED -> QUEUED with a fresh set of attempts (e.g. after fixing the API key). The stored PDF is reused. */
    @Transactional
    public InvoiceResponse requeue(Long invoiceId, String reason) {
        String why = requireReason(reason);
        Invoice invoice = lock(invoiceId);
        ExtractionJob job = jobRepository.findById(invoiceId).orElse(null);
        if (job == null || invoice.getStatus() != InvoiceStatus.FAILED) {
            throw new WorkflowConflictException("Only a FAILED batch invoice can be requeued.");
        }
        job.requeue(LocalDateTime.now());
        invoice.setFailureReason(null);
        workflow.transition(invoice, InvoiceStatus.QUEUED, actor(), null, why);
        return InvoiceResponse.fromEntity(invoice, versionWriter.currentVersion(invoiceId));
    }

    private Invoice lock(Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (invoice.isLegacy()) {
            throw new WorkflowConflictException("Invoice " + invoiceId + " is a legacy invoice outside the approval workflow.");
        }
        return invoice;
    }

    /** @return the current version, after checking version == submitted == current */
    private int requireSubmittedVersion(Invoice invoice, Integer version) {
        if (version == null) {
            throw new InvalidRequestException("version is required (the version you are deciding on)",
                    Collections.singletonList(new FieldErrorDto("version", "version is required")));
        }
        if (invoice.getStatus() != InvoiceStatus.PENDING_APPROVAL) {
            throw new WorkflowConflictException("Invoice " + invoice.getId() + " is " + invoice.getStatus()
                    + ", not waiting for approval.");
        }
        int current = versionWriter.currentVersion(invoice.getId());
        if (!version.equals(invoice.getSubmittedVersion()) || version != current) {
            throw new VersionConflictException("Version " + version + " is not the submitted, current version (submitted: "
                    + invoice.getSubmittedVersion() + ", current: " + current + "). Reload before deciding.", current);
        }
        return current;
    }

    private Long actor() {
        return currentUser.require().getId();
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidRequestException("reason is required (1-" + MAX_REASON_LENGTH + " characters)",
                    Collections.singletonList(new FieldErrorDto("reason", "reason is required (1-" + MAX_REASON_LENGTH + " characters)")));
        }
        return trimmed;
    }

    private static String optional(String note, String fallback) {
        if (note == null || note.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = note.trim();
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }
}
