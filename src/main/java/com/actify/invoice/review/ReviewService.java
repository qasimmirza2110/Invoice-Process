package com.actify.invoice.review;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.WorkflowDtos.EventResponse;
import com.actify.invoice.dto.WorkflowDtos.ReviewReasonResponse;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.actify.invoice.entity.InvoiceReviewReason;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.InvoiceWorkflowEvent;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.exception.VersionConflictException;
import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.InvoiceReviewReasonRepository;
import com.actify.invoice.repository.InvoiceWorkflowEventRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.validation.InvoiceExtractionValidator;
import com.actify.invoice.validation.ReviewReason;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import com.actify.invoice.workflow.WorkflowService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The reviewer's side of the workflow (all callers have passed InvoiceAccessPolicy first):
 *  - read the stored review reasons (why the invoice needs attention) and the live checks of the current data;
 *  - submit ONE exact version for admin approval. Corrections themselves go through the existing
 *    InvoiceEditService (PUT /api/invoices/{id}), which creates V2, V3... and never touches V1 or the PDF.
 */
@Service
public class ReviewService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceReviewReasonRepository reasonRepository;
    private final InvoiceWorkflowEventRepository eventRepository;
    private final InvoiceExtractionValidator validator;
    private final InvoiceVersionWriter versionWriter;
    private final WorkflowService workflow;
    private final CurrentUserService currentUser;

    public ReviewService(InvoiceRepository invoiceRepository, InvoiceReviewReasonRepository reasonRepository,
                         InvoiceWorkflowEventRepository eventRepository, InvoiceExtractionValidator validator,
                         InvoiceVersionWriter versionWriter, WorkflowService workflow, CurrentUserService currentUser) {
        this.invoiceRepository = invoiceRepository;
        this.reasonRepository = reasonRepository;
        this.eventRepository = eventRepository;
        this.validator = validator;
        this.versionWriter = versionWriter;
        this.workflow = workflow;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ReviewReasonResponse> reviewReasons(Long invoiceId) {
        List<ReviewReasonResponse> result = new ArrayList<>();
        for (InvoiceReviewReason reason : reasonRepository.findByInvoiceId(invoiceId)) {
            result.add(new ReviewReasonResponse(reason));
        }
        return result;
    }

    /** The automatic checks run on the invoice's CURRENT data (after any corrections). Empty = all pass. */
    @Transactional(readOnly = true)
    public List<ReviewReason> currentChecks(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        return validator.findReasons(currentValues(invoice));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> events(Long invoiceId) {
        List<EventResponse> result = new ArrayList<>();
        for (InvoiceWorkflowEvent event : eventRepository.findByInvoiceIdOrderByIdAsc(invoiceId)) {
            result.add(new EventResponse(event));
        }
        return result;
    }

    /**
     * NEEDS_REVIEW -> PENDING_APPROVAL for exactly `version`:
     *  - only the assigned reviewer;
     *  - version must be the CURRENT version (409 otherwise: someone saved in between);
     *  - the current data must pass every automatic check (422 with field errors otherwise). There is no override
     *    for totals that do not reconcile: correct them, or tell the admin, who can reject the invoice.
     */
    @Transactional
    public InvoiceResponse submit(Long invoiceId, Integer version) {
        AuthenticatedUser user = currentUser.require();
        if (version == null) {
            throw new InvalidRequestException("version is required (the version you reviewed)",
                    Collections.singletonList(new FieldErrorDto("version", "version is required")));
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!user.getId().equals(invoice.getAssignedReviewerId())) {
            throw new AccessDeniedException("Only the assigned reviewer can submit this invoice.");
        }
        if (invoice.getStatus() != InvoiceStatus.NEEDS_REVIEW) {
            throw new WorkflowConflictException("Invoice " + invoiceId + " is " + invoice.getStatus() + ", not NEEDS_REVIEW.");
        }
        int current = versionWriter.currentVersion(invoiceId);
        if (version != current) {
            throw new VersionConflictException(current, version);
        }
        List<FieldErrorDto> problems = validator.findProblems(currentValues(invoice));
        if (!problems.isEmpty()) {
            throw new InvoiceValidationException("The current version still fails automatic checks; correct it before submitting.",
                    problems);
        }
        invoice.setSubmittedVersion(version);
        workflow.transition(invoice, InvoiceStatus.PENDING_APPROVAL, user.getId(), version, "Submitted for approval");
        return InvoiceResponse.fromEntity(invoice, current);
    }

    /** The invoice's current columns in the shape the validator understands. */
    static InvoiceExtractionResult currentValues(Invoice invoice) {
        InvoiceExtractionResult v = new InvoiceExtractionResult();
        v.setInvoiceNumber(invoice.getInvoiceNumber());
        v.setInvoiceDate(invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
        v.setSellerName(invoice.getSellerName());
        v.setSellerTaxId(invoice.getSellerTaxId());
        v.setBuyerName(invoice.getBuyerName());
        v.setBuyerTaxId(invoice.getBuyerTaxId());
        v.setCurrency(invoice.getCurrency());
        v.setSubtotal(invoice.getSubtotal());
        v.setDiscountAmount(invoice.getDiscountAmount());
        v.setShippingAmount(invoice.getShippingAmount());
        v.setTaxAmount(invoice.getTaxAmount());
        v.setTotalAmount(invoice.getTotalAmount());
        List<InvoiceItemDto> items = new ArrayList<>();
        for (InvoiceItem item : invoice.getItems()) {
            items.add(new InvoiceItemDto(item.getDescription(), item.getQuantity(), item.getUnitPrice(), item.getTaxRate(),
                    item.getLineTotal()));
        }
        v.setItems(items);
        return v;
    }
}
