package com.actify.invoice.review;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.WorkflowDtos.AssignmentResult;
import com.actify.invoice.entity.Category;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.ReviewerCategory;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.UserNotFoundException;
import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.intake.CategoryCodes;
import com.actify.invoice.repository.CategoryRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.ReviewerCategoryRepository;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ADMIN work routing (ADMIN-only URLs):
 *  - which categories each reviewer handles (reviewer_categories: data, not hard-coded names);
 *  - the category of an invoice;
 *  - assigning invoices to a reviewer, one or many at once. A reviewer may only receive invoices of a category
 *    they handle. Reviewers do not claim work themselves in this version.
 * Assigning is not a status change, so it writes no workflow event; the assignment is visible on the invoice.
 */
@Service
public class ReviewAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAssignmentService.class);

    /** Assignment and category changes are allowed until the reviewer has submitted. */
    private static final Set<InvoiceStatus> ASSIGNABLE =
            EnumSet.of(InvoiceStatus.QUEUED, InvoiceStatus.PROCESSING, InvoiceStatus.NEEDS_REVIEW);

    private final AppUserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ReviewerCategoryRepository reviewerCategories;
    private final InvoiceRepository invoiceRepository;
    private final CategoryCodes categoryCodes;

    public ReviewAssignmentService(AppUserRepository userRepository, CategoryRepository categoryRepository,
                                   ReviewerCategoryRepository reviewerCategories, InvoiceRepository invoiceRepository,
                                   CategoryCodes categoryCodes) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.reviewerCategories = reviewerCategories;
        this.invoiceRepository = invoiceRepository;
        this.categoryCodes = categoryCodes;
    }

    @Transactional(readOnly = true)
    public List<String> reviewerCategories(Long reviewerId) {
        requireReviewer(reviewerId);
        return reviewerCategories.findCategoryCodesOfReviewer(reviewerId);
    }

    /** Replaces the reviewer's category list with exactly these (active) categories. */
    @Transactional
    public List<String> setReviewerCategories(Long reviewerId, List<String> codes) {
        AppUser reviewer = requireReviewer(reviewerId);
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes == null ? Collections.<String>emptyList() : codes) {
            normalized.add(categoryCodes.requireActiveOrNull(code));
        }
        normalized.remove(null);
        reviewerCategories.deleteAllOfReviewer(reviewerId);
        reviewerCategories.flush();
        for (String code : normalized) {
            Category category = categoryRepository.findById(code).orElseThrow(IllegalStateException::new);
            reviewerCategories.save(new ReviewerCategory(reviewer, category));
        }
        log.info("Reviewer {} now handles {}", reviewerId, normalized);
        return new ArrayList<>(normalized);
    }

    /** Changes an invoice's category. A reviewer who does not handle the new category is unassigned. */
    @Transactional
    public InvoiceResponse setInvoiceCategory(Long invoiceId, String code) {
        Invoice invoice = requireWorkflowInvoice(invoiceId);
        requireAssignable(invoice);
        String normalized = categoryCodes.requireActiveOrNull(code);
        invoice.setCategoryCode(normalized);
        if (invoice.getAssignedReviewerId() != null
                && (normalized == null || !reviewerCategories.reviewerHasCategory(invoice.getAssignedReviewerId(), normalized))) {
            invoice.setAssignedReviewer(null);
        }
        // Built inside the transaction: the response reads the (lazy) line items.
        return InvoiceResponse.fromEntity(invoice);
    }

    /** Assigns (or, with reviewerId null, unassigns) each invoice; returns one result per invoice id. */
    @Transactional
    public List<AssignmentResult> assign(List<Long> invoiceIds, Long reviewerId) {
        if (invoiceIds == null || invoiceIds.isEmpty() || invoiceIds.size() > 200) {
            throw new InvalidRequestException("invoiceIds must contain 1-200 ids", Collections.singletonList(
                    new FieldErrorDto("invoiceIds", "invoiceIds must contain 1-200 ids")));
        }
        AppUser reviewer = reviewerId == null ? null : requireReviewer(reviewerId);
        if (reviewer != null && !reviewer.isEnabled()) {
            throw new WorkflowConflictException("Reviewer " + reviewerId + " is disabled.");
        }
        List<AssignmentResult> results = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(invoiceIds)) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(id).orElse(null);
            if (invoice == null || invoice.isLegacy()) {
                results.add(new AssignmentResult(id, false, "No such workflow invoice."));
            } else if (!ASSIGNABLE.contains(invoice.getStatus())) {
                results.add(new AssignmentResult(id, false, "Status " + invoice.getStatus() + " cannot be (re)assigned."));
            } else if (reviewer != null && invoice.getCategoryCode() == null) {
                results.add(new AssignmentResult(id, false, "Set the invoice's category first."));
            } else if (reviewer != null && !reviewerCategories.reviewerHasCategory(reviewer.getId(), invoice.getCategoryCode())) {
                results.add(new AssignmentResult(id, false, "The reviewer does not handle category " + invoice.getCategoryCode() + "."));
            } else {
                invoice.setAssignedReviewer(reviewer);
                results.add(new AssignmentResult(id, true, reviewer == null ? "Unassigned." : "Assigned."));
            }
        }
        return results;
    }

    private AppUser requireReviewer(Long reviewerId) {
        AppUser user = userRepository.findById(reviewerId).orElseThrow(() -> new UserNotFoundException(reviewerId));
        if (user.getRole() != Role.REVIEWER) {
            throw new WorkflowConflictException("User " + reviewerId + " is not a REVIEWER.");
        }
        return user;
    }

    private Invoice requireWorkflowInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (invoice.isLegacy()) {
            throw new WorkflowConflictException("Invoice " + invoiceId + " is a legacy invoice outside the review workflow.");
        }
        return invoice;
    }

    private void requireAssignable(Invoice invoice) {
        if (!ASSIGNABLE.contains(invoice.getStatus())) {
            throw new WorkflowConflictException("Invoice " + invoice.getId() + " is " + invoice.getStatus()
                    + "; its category can no longer be changed.");
        }
    }
}
