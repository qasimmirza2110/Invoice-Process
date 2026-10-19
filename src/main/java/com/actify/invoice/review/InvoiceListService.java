package com.actify.invoice.review;

import com.actify.invoice.dto.WorkflowDtos.InvoiceSummary;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.ReviewReasonCounts;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
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

    public InvoiceListService(InvoiceRepository invoiceRepository, ReviewReasonCounts reasonCounts,
                              CurrentUserService currentUser) {
        this.invoiceRepository = invoiceRepository;
        this.reasonCounts = reasonCounts;
        this.currentUser = currentUser;
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
        Map<Long, Long> counts = reasonCounts.forInvoices(ids);
        List<InvoiceSummary> rows = new ArrayList<>();
        for (Invoice invoice : result.getContent()) {
            rows.add(new InvoiceSummary(invoice, counts.getOrDefault(invoice.getId(), 0L)));
        }
        return rows;
    }
}
