package com.actify.invoice.dto;

import com.actify.invoice.entity.VersionChangeSource;
import com.actify.invoice.versioning.FieldChange;
import com.actify.invoice.versioning.InvoiceSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One invoice version. The list endpoint leaves snapshot out (null, omitted from JSON);
 * the single-version endpoint includes the full snapshot.
 */
public class InvoiceVersionResponse {

    private final Long invoiceId;
    private final Integer versionNumber;
    private final VersionChangeSource changeSource;
    private final String changeReason;
    private final String changedBy;              // "user:<id>" of the editor; null for background work
    private final String changedByName;          // e-mail before the "@" of that user; null for background work
    private final LocalDateTime createdAt;
    private final SourceDocumentDto sourceDocument;
    private final List<FieldChange> changedFields;
    private final InvoiceSnapshot snapshot;

    public InvoiceVersionResponse(Long invoiceId, Integer versionNumber, VersionChangeSource changeSource,
                                  String changeReason, String changedBy, String changedByName, LocalDateTime createdAt,
                                  SourceDocumentDto sourceDocument, List<FieldChange> changedFields,
                                  InvoiceSnapshot snapshot) {
        this.invoiceId = invoiceId;
        this.versionNumber = versionNumber;
        this.changeSource = changeSource;
        this.changeReason = changeReason;
        this.changedBy = changedBy;
        this.changedByName = changedByName;
        this.createdAt = createdAt;
        this.sourceDocument = sourceDocument;
        this.changedFields = changedFields;
        this.snapshot = snapshot;
    }

    public Long getInvoiceId() { return invoiceId; }
    public Integer getVersionNumber() { return versionNumber; }
    public VersionChangeSource getChangeSource() { return changeSource; }
    public String getChangeReason() { return changeReason; }
    public String getChangedBy() { return changedBy; }
    public String getChangedByName() { return changedByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public SourceDocumentDto getSourceDocument() { return sourceDocument; }
    public List<FieldChange> getChangedFields() { return changedFields; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public InvoiceSnapshot getSnapshot() { return snapshot; }
}
