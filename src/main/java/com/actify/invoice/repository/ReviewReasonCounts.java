package com.actify.invoice.repository;

import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Number of V1 review reasons per invoice, for a page of the invoice list (one query instead of one per row). */
@Component
public class ReviewReasonCounts {

    @PersistenceContext
    private EntityManager entityManager;

    public Map<Long, Long> forInvoices(Collection<Long> invoiceIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (invoiceIds.isEmpty()) {
            return counts;
        }
        List<?> rows = entityManager.createQuery("select r.invoice.id, count(r) from InvoiceReviewReason r "
                + "where r.invoice.id in :ids group by r.invoice.id")
                .setParameter("ids", invoiceIds)
                .getResultList();
        for (Object row : rows) {
            Object[] cols = (Object[]) row;
            counts.put((Long) cols[0], (Long) cols[1]);
        }
        return counts;
    }
}
