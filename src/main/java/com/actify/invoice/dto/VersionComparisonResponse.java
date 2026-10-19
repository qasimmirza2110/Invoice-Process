package com.actify.invoice.dto;

import com.actify.invoice.versioning.FieldChange;

import java.util.List;

/** Field-level differences between two stored versions of the same invoice. */
public class VersionComparisonResponse {

    private final Long invoiceId;
    private final int fromVersion;
    private final int toVersion;
    private final List<FieldChange> differences;

    public VersionComparisonResponse(Long invoiceId, int fromVersion, int toVersion, List<FieldChange> differences) {
        this.invoiceId = invoiceId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.differences = differences;
    }

    public Long getInvoiceId() { return invoiceId; }
    public int getFromVersion() { return fromVersion; }
    public int getToVersion() { return toVersion; }
    public List<FieldChange> getDifferences() { return differences; }
}
