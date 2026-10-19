package com.actify.invoice.security;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Object-level authorization: "may THIS user see THIS invoice / document?"
 * The URL rules in SecurityConfig only know the role; they cannot know who owns invoice 42.
 *
 * A user who may not see an invoice gets exactly the same 404 as for an invoice that does not exist,
 * so ids cannot be probed (OWASP: insecure direct object reference).
 *
 *  - ADMIN: every invoice (including legacy invoices, which have no owner).
 *  - VENDOR: only invoices they uploaded (invoices.owner_user_id).
 *  - REVIEWER: only invoices assigned to them (see canRead).
 * Every controller method that takes an invoice or document id calls this policy first.
 */
@Service
public class InvoiceAccessPolicy {

    private final CurrentUserService currentUser;
    private final InvoiceRepository invoiceRepository;

    public InvoiceAccessPolicy(CurrentUserService currentUser, InvoiceRepository invoiceRepository) {
        this.currentUser = currentUser;
        this.invoiceRepository = invoiceRepository;
    }

    /** 404 unless the invoice exists and the current user may read it. */
    @Transactional(readOnly = true)
    public void requireReadableInvoice(Long invoiceId) {
        AuthenticatedUser user = currentUser.require();
        if (user.isAdmin()) {
            return; // existence is still checked by the service (same 404)
        }
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || !canRead(user, invoice)) {
            throw new InvoiceNotFoundException(invoiceId);
        }
    }

    /**
     * A PDF is authorized through the invoice that owns it - never by its random document id alone.
     * A document that no invoice references is not served to anyone.
     */
    @Transactional(readOnly = true)
    public void requireReadableDocument(String documentId) {
        AuthenticatedUser user = currentUser.require();
        Invoice owner = invoiceRepository.findFirstBySourceDocument_IdOrderByIdAsc(documentId).orElse(null);
        if (owner == null || !canRead(user, owner)) {
            throw new DocumentNotFoundException();
        }
    }

    /**
     * Who may save a correction (PUT /api/invoices/{id}):
     *  - the ASSIGNED REVIEWER of a workflow invoice (the edit service then requires status NEEDS_REVIEW, else 409);
     *  - an ADMIN, but only on LEGACY invoices (the old single-file flow). Workflow invoices are never edited by an
     *    admin directly: the admin sends them back to the reviewer, so every change is reviewed (403).
     * Anyone who may not even read the invoice gets 404.
     */
    @Transactional(readOnly = true)
    public void requireCanCorrect(Long invoiceId) {
        AuthenticatedUser user = currentUser.require();
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || !canRead(user, invoice)) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        if (user.isAdmin() && !invoice.isLegacy()) {
            throw new AccessDeniedException("Administrators do not edit workflow invoices; send it back to the reviewer.");
        }
        if (!user.isAdmin() && user.getRole() != Role.REVIEWER) {
            throw new AccessDeniedException("Only the assigned reviewer can correct this invoice.");
        }
    }

    public boolean canRead(AuthenticatedUser user, Invoice invoice) {
        switch (user.getRole()) {
            case ADMIN:
                return true;
            case VENDOR:
                return user.getId().equals(invoice.getOwnerUserId());
            case REVIEWER:
                return user.getId().equals(invoice.getAssignedReviewerId());
            default:
                return false;
        }
    }
}
