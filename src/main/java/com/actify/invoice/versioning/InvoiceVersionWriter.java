package com.actify.invoice.versioning;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceVersion;
import com.actify.invoice.entity.VersionChangeSource;
import com.actify.invoice.exception.VersionConflictException;
import com.actify.invoice.repository.InvoiceVersionRepository;
import com.actify.invoice.security.CurrentUserService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

/**
 * The only place that inserts invoice_versions rows. It always runs inside the caller's transaction,
 * so a failure here rolls back the invoice/items change it belongs to.
 */
@Component
public class InvoiceVersionWriter {

    static final String UNIQUE_VERSION_CONSTRAINT = "uk_invoice_versions_invoice_version";

    private final InvoiceVersionRepository versionRepository;
    private final InvoiceSnapshotMapper snapshotMapper;
    private final CurrentUserService currentUser;

    @PersistenceContext
    private EntityManager entityManager;

    public InvoiceVersionWriter(InvoiceVersionRepository versionRepository, InvoiceSnapshotMapper snapshotMapper,
                                CurrentUserService currentUser) {
        this.versionRepository = versionRepository;
        this.snapshotMapper = snapshotMapper;
        this.currentUser = currentUser;
    }

    public int currentVersion(Long invoiceId) {
        return versionRepository.findCurrentVersionNumber(invoiceId);
    }

    /**
     * V1 of a freshly extracted invoice. Flushes and re-reads the invoice first, so the snapshot holds
     * exactly what PostgreSQL stored (e.g. after numeric(19,2) rounding), not the in-memory LLM values.
     */
    public int recordExtraction(Invoice savedInvoice) {
        entityManager.flush();
        entityManager.refresh(savedInvoice);
        InvoiceSnapshot snapshot = snapshotMapper.fromEntity(savedInvoice);
        append(savedInvoice, 1, VersionChangeSource.LLM_EXTRACTION, null, snapshot, null);
        return 1;
    }

    /**
     * Inserts one version and flushes immediately, so a duplicate (invoice_id, version_number) is reported
     * here as a 409 and rolls the whole save back, instead of surfacing later as a generic commit error.
     *
     * changed_by is "user:<id>" of the logged-in user, or null when no user is logged in (background work).
     */
    public InvoiceVersion append(Invoice invoice, int versionNumber, VersionChangeSource source, String reason,
                                 InvoiceSnapshot snapshot, List<FieldChange> changes) {
        InvoiceVersion version = new InvoiceVersion(invoice, versionNumber, source, reason,
                snapshotMapper.toJson(snapshot), changes == null ? null : snapshotMapper.toJson(changes),
                invoice.getSourceDocument(), currentUser.actorLabelOrNull());
        try {
            InvoiceVersion saved = versionRepository.save(version);
            entityManager.flush();
            return saved;
        } catch (DataIntegrityViolationException | javax.persistence.PersistenceException e) {
            if (isDuplicateVersion(e)) {
                throw new VersionConflictException("Another save created version " + versionNumber
                        + " of this invoice first. Reload the latest version before saving.", versionNumber);
            }
            throw e;
        }
    }

    private boolean isDuplicateVersion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException) {
                String name = ((ConstraintViolationException) t).getConstraintName();
                return name != null && name.contains(UNIQUE_VERSION_CONSTRAINT);
            }
        }
        return false;
    }
}
