package com.actify.invoice.entity;

/**
 * Business/workflow state of an invoice (invoices.status, varchar(20): every name must stay within 20 characters).
 *
 * Legacy single-file flow (POST /api/invoices/extract), unchanged:  PROCESSING -> EXTRACTED | FAILED
 * New batch workflow:
 *   QUEUED -> PROCESSING -> NEEDS_REVIEW -> PENDING_APPROVAL -> APPROVED | REJECTED
 *             PROCESSING -> QUEUED (retry / stale claim)  |  PROCESSING -> FAILED
 * The allowed moves are listed in WorkflowTransitions. Queue mechanics live in extraction_jobs,
 * the invoice content history in invoice_versions.
 */
public enum InvoiceStatus {
    /** Entity default; never used by any flow. Kept so old values stay readable. */
    CREATED,
    PROCESSING,
    /** Legacy flow only: extracted and valid, outside the review/approval workflow. */
    EXTRACTED,
    FAILED,
    /** Accepted into a batch, PDF stored, waiting for the extraction worker. */
    QUEUED,
    /** Extracted (with or without problems); waiting for a reviewer. */
    NEEDS_REVIEW,
    /** A reviewer submitted a specific version; waiting for an admin decision. */
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
