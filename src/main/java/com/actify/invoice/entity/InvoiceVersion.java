package com.actify.invoice.entity;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * One complete, append-only snapshot of an invoice (header + every line item) at a point in time.
 * V1 is the original extraction, V2+ are saved corrections. The invoices/invoice_items rows are
 * only the current working copy; this table is the history.
 *
 * @Immutable plus updatable=false columns: Hibernate never issues an UPDATE for a version, and the
 * repository exposes no delete. The unique constraint is the last line of defence against two
 * saves ever getting the same version number. Its index also serves "ORDER BY version_number DESC".
 */
@Entity
@Immutable
@Table(name = "invoice_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoice_versions_invoice_version",
                columnNames = {"invoice_id", "version_number"}))
public class InvoiceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_versions_invoice"))
    private Invoice invoice;

    @Column(name = "version_number", nullable = false, updatable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_source", length = 30, nullable = false, updatable = false)
    private VersionChangeSource changeSource;

    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    // "user:<id>" of the logged-in user who caused this version. null for versions written before
    // authentication existed, or by background work without a user. Never filled with a guess.
    @Column(name = "changed_by", length = 100, updatable = false)
    private String changedBy;

    // JSON text bound with an explicit ::jsonb cast, so no extra Hibernate type library is needed.
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "snapshot_data", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String snapshotData;

    // null for a version that has no predecessor (V1 / legacy baseline).
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "changed_fields", columnDefinition = "jsonb", updatable = false)
    private String changedFields;

    // The same immutable source PDF for every version of an invoice; null for legacy invoices that never had one.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_versions_source_document"))
    private InvoiceDocument sourceDocument;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InvoiceVersion() {
    }

    public InvoiceVersion(Invoice invoice, int versionNumber, VersionChangeSource changeSource, String changeReason,
                          String snapshotData, String changedFields, InvoiceDocument sourceDocument,
                          String changedBy) {
        this.invoice = invoice;
        this.versionNumber = versionNumber;
        this.changeSource = changeSource;
        this.changeReason = changeReason;
        this.snapshotData = snapshotData;
        this.changedFields = changedFields;
        this.sourceDocument = sourceDocument;
        this.changedBy = changedBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Invoice getInvoice() { return invoice; }
    public Integer getVersionNumber() { return versionNumber; }
    public VersionChangeSource getChangeSource() { return changeSource; }
    public String getChangeReason() { return changeReason; }
    public String getChangedBy() { return changedBy; }
    public String getSnapshotData() { return snapshotData; }
    public String getChangedFields() { return changedFields; }
    public InvoiceDocument getSourceDocument() { return sourceDocument; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
