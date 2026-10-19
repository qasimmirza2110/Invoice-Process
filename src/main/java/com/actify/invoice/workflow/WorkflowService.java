package com.actify.invoice.workflow;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.InvoiceWorkflowEvent;
import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.repository.InvoiceWorkflowEventRepository;
import com.actify.invoice.user.AppUser;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

/**
 * The ONLY place that changes invoices.status in the batch workflow. Each change:
 *   1. is checked against WorkflowTransitions (otherwise 409),
 *   2. sets the new status,
 *   3. appends one invoice_workflow_events row (who, from, to, version, reason).
 * Always runs inside the caller's transaction, so the status and its audit row are committed together.
 */
@Service
public class WorkflowService {

    private final InvoiceWorkflowEventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public WorkflowService(InvoiceWorkflowEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** First event of a new batch invoice (from "nothing" to QUEUED). The invoice must already be saved. */
    public void recordCreated(Invoice invoice, Long actorUserId) {
        if (invoice.getStatus() != InvoiceStatus.QUEUED) {
            throw new IllegalStateException("A new workflow invoice starts as QUEUED");
        }
        eventRepository.save(new InvoiceWorkflowEvent(invoice, null, InvoiceStatus.QUEUED, actor(actorUserId), null, null));
    }

    /**
     * @param actorUserId  the person, or null for the system (extraction worker)
     * @param version      the invoice version the change is about (submit/approve/reject), or null
     * @param reason       why (required by the callers for reject/send-back/reopen/requeue), or null
     */
    public void transition(Invoice invoice, InvoiceStatus to, Long actorUserId, Integer version, String reason) {
        InvoiceStatus from = invoice.getStatus();
        if (!WorkflowTransitions.isAllowed(from, to)) {
            throw new WorkflowConflictException("Invoice " + invoice.getId() + " cannot go from " + from + " to " + to + ".");
        }
        invoice.setStatus(to);
        eventRepository.save(new InvoiceWorkflowEvent(invoice, from, to, actor(actorUserId), version, reason));
    }

    // A reference (no SELECT): only the id is needed for the foreign key.
    private AppUser actor(Long actorUserId) {
        return actorUserId == null ? null : entityManager.getReference(AppUser.class, actorUserId);
    }
}
