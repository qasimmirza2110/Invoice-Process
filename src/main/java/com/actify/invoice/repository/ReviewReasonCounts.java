package com.actify.invoice.repository;

import com.actify.invoice.validation.ReviewReasonCode;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Number of V1 review reasons per invoice, for a page of the invoice list (one query instead of one per row). */
@Component
public class ReviewReasonCounts {

    @PersistenceContext
    private EntityManager entityManager;

    public Map<Long, Long> forInvoices(Collection<Long> invoiceIds) {
        return forInvoices(invoiceIds, null);
    }

    /** @param onlyCodes count only these codes (null = all codes). */
    public Map<Long, Long> forInvoices(Collection<Long> invoiceIds, Set<ReviewReasonCode> onlyCodes) {
        Map<Long, Long> counts = new HashMap<>();
        if (invoiceIds.isEmpty()) {
            return counts;
        }
        Query query = entityManager.createQuery("select r.invoice.id, count(r) from InvoiceReviewReason r "
                + "where r.invoice.id in :ids" + (onlyCodes == null ? "" : " and r.code in :codes")
                + " group by r.invoice.id")
                .setParameter("ids", invoiceIds);
        if (onlyCodes != null) {
            query.setParameter("codes", onlyCodes);
        }
        List<?> rows = query.getResultList();
        for (Object row : rows) {
            Object[] cols = (Object[]) row;
            counts.put((Long) cols[0], (Long) cols[1]);
        }
        return counts;
    }
}
