package com.actify.invoice.versioning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One difference between two snapshots. field uses the same paths as the validation errors
 * ("totalAmount", "items[1].quantity"); a whole added/removed line item is "items[2]".
 */
@JsonPropertyOrder({"field", "changeType", "from", "to"})
public class FieldChange {

    public static final String CHANGED = "CHANGED";
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";

    private String field;
    private String changeType;
    private String from;
    private String to;

    public FieldChange() {
    }

    public FieldChange(String field, String changeType, String from, String to) {
        this.field = field;
        this.changeType = changeType;
        this.from = from;
        this.to = to;
    }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
