package com.actify.invoice.workflow;

import com.actify.invoice.entity.InvoiceStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed status changes of the batch workflow, in one table. Anything not listed is refused (HTTP 409).
 *
 *   from \ to          QUEUED  PROCESSING  NEEDS_REVIEW  PENDING_APPROVAL  APPROVED  REJECTED  FAILED
 *   (new invoice)        x
 *   QUEUED                         x                                                            (*)
 *   PROCESSING           x                     x                                                  x
 *   NEEDS_REVIEW                                              x
 *   PENDING_APPROVAL                           x                              x          x
 *   APPROVED                                   x  (reopen)
 *   REJECTED                                   x  (reopen)
 *   FAILED               x  (admin requeue)
 *
 * (*) not allowed: a queued job always goes through PROCESSING.
 * The legacy statuses CREATED and EXTRACTED are not part of this workflow; legacy invoices are never enrolled automatically.
 */
public final class WorkflowTransitions {

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED = new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED.put(InvoiceStatus.QUEUED, EnumSet.of(InvoiceStatus.PROCESSING));
        ALLOWED.put(InvoiceStatus.PROCESSING, EnumSet.of(InvoiceStatus.QUEUED, InvoiceStatus.NEEDS_REVIEW, InvoiceStatus.FAILED));
        ALLOWED.put(InvoiceStatus.NEEDS_REVIEW, EnumSet.of(InvoiceStatus.PENDING_APPROVAL));
        ALLOWED.put(InvoiceStatus.PENDING_APPROVAL,
                EnumSet.of(InvoiceStatus.NEEDS_REVIEW, InvoiceStatus.APPROVED, InvoiceStatus.REJECTED));
        ALLOWED.put(InvoiceStatus.APPROVED, EnumSet.of(InvoiceStatus.NEEDS_REVIEW));
        ALLOWED.put(InvoiceStatus.REJECTED, EnumSet.of(InvoiceStatus.NEEDS_REVIEW));
        ALLOWED.put(InvoiceStatus.FAILED, EnumSet.of(InvoiceStatus.QUEUED));
    }

    private WorkflowTransitions() {
    }

    /** from == null means "a new invoice"; the only allowed first status is QUEUED. */
    public static boolean isAllowed(InvoiceStatus from, InvoiceStatus to) {
        if (from == null) {
            return to == InvoiceStatus.QUEUED;
        }
        return ALLOWED.getOrDefault(from, Collections.<InvoiceStatus>emptySet()).contains(to);
    }
}
