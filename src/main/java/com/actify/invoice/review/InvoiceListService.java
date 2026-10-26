package com.actify.invoice.review;

import com.actify.invoice.dto.WorkflowDtos.InvoiceSummary;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.ReviewReasonCounts;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.user.Role;
import com.actify.invoice.user.UserNames;
import com.actify.invoice.validation.ReviewReasonCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * GET /api/invoices - the list every role sees, ALWAYS narrowed on the server to what the role may read:
 *   VENDOR -> own uploads only; REVIEWER -> invoices assigned to them only; ADMIN -> every workflow invoice
 *   (or, with legacy=true, the invoices created before the workflow existed).
 * The optional filters can only narrow this further; they can never widen it.
 */
@Service
public class InvoiceListService {

    public static final int MAX_PAGE_SIZE = 200;

    private final InvoiceRepository invoiceRepository;
    private final ReviewReasonCounts reasonCounts;
    private final CurrentUserService currentUser;
    private final UserNames userNames;

    public InvoiceListService(InvoiceRepository invoiceRepository, ReviewReasonCounts reasonCounts,
                              CurrentUserService currentUser, UserNames userNames) {
        this.invoiceRepository = invoiceRepository;
        this.reasonCounts = reasonCounts;
        this.currentUser = currentUser;
        this.userNames = userNames;
    }

    @Transactional(readOnly = true)
    public List<InvoiceSummary> list(InvoiceStatus status, String categoryCode, Long batchId, Boolean unassigned,
                                     boolean legacy, int page, int size) {
        AuthenticatedUser user = currentUser.require();
        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            switch (user.getRole()) {
                case VENDOR:
                    p.add(cb.equal(root.get("ownerUserId"), user.getId()));
                    break;
                case REVIEWER:
                    p.add(cb.equal(root.get("assignedReviewerId"), user.getId()));
                    break;
                default: // ADMIN
                    p.add(legacy ? cb.isNull(root.get("ownerUserId")) : cb.isNotNull(root.get("ownerUserId")));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (categoryCode != null && !categoryCode.trim().isEmpty()) {
                p.add(cb.equal(root.get("categoryCode"), categoryCode.trim().toUpperCase(java.util.Locale.ROOT)));
            }
            if (batchId != null) {
                p.add(cb.equal(root.get("batchId"), batchId));
            }
            if (Boolean.TRUE.equals(unassigned)) {
                p.add(cb.isNull(root.get("assignedReviewerId")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<Invoice> result = invoiceRepository.findAll(spec,
                PageRequest.of(Math.max(0, page), pageSize, Sort.by(Sort.Direction.ASC, "id")));

        List<Long> ids = new ArrayList<>();
        for (Invoice invoice : result.getContent()) {
            ids.add(invoice.getId());
        }
        // A vendor's count leaves out cross-invoice duplicate reasons (they would reveal other vendors' uploads).
        Map<Long, Long> counts = reasonCounts.forInvoices(ids,
                user.getRole() == Role.VENDOR ? ReviewReasonCode.visibleToVendor() : null);
        // Reviewer names for ADMIN / REVIEWER lists (one query); vendors do not get the reviewer's identity.
        Map<Long, String> names = Collections.emptyMap();
        if (user.getRole() != Role.VENDOR) {
            List<Long> reviewerIds = new ArrayList<>();
            for (Invoice invoice : result.getContent()) {
                reviewerIds.add(invoice.getAssignedReviewerId());
            }
            names = userNames.namesById(reviewerIds);
        }
        List<InvoiceSummary> rows = new ArrayList<>();
        for (Invoice invoice : result.getContent()) {
            Long reviewerId = invoice.getAssignedReviewerId();
            String reviewerName = user.getRole() == Role.VENDOR || reviewerId == null ? null
                    : names.getOrDefault(reviewerId, UserNames.UNKNOWN_USER);
            rows.add(new InvoiceSummary(invoice, counts.getOrDefault(invoice.getId(), 0L), reviewerName));
        }
        return rows;
    }
}
