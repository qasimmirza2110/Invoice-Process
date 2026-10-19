package com.actify.invoice.dashboard;

import com.actify.invoice.versioning.FieldChange;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Correction rate": how much reviewers changed the extracted data before submitting it. This measures human
 * EDITING, not model accuracy - a reviewer may also change a correct value, or miss a wrong one.
 *
 *   fields compared = 12 header fields + 5 fields per line item (using the longer item list of the two versions)
 *   fields changed  = changed header/item fields; an added or removed line item counts as 5 changed fields
 *   rate            = fields changed / fields compared   (over all submissions in the period)
 */
public final class CorrectionStats {

    static final int HEADER_FIELDS = 12;
    static final int FIELDS_PER_ITEM = 5;

    private long submissions;
    private long fieldsCompared;
    private long fieldsChanged;
    private final Map<String, Long> changesByField = new TreeMap<>();

    /** Adds one submission: the diff between V1 (the extraction) and the submitted version. */
    public void add(List<FieldChange> changes, int itemsInV1, int itemsInSubmitted) {
        submissions++;
        fieldsCompared += HEADER_FIELDS + (long) FIELDS_PER_ITEM * Math.max(itemsInV1, itemsInSubmitted);
        for (FieldChange change : changes) {
            boolean wholeItem = FieldChange.ADDED.equals(change.getChangeType()) || FieldChange.REMOVED.equals(change.getChangeType());
            fieldsChanged += wholeItem ? FIELDS_PER_ITEM : 1;
            changesByField.merge(groupName(change), 1L, Long::sum);
        }
    }

    /** "items[3].quantity" -> "items[].quantity", so all line items of one kind are counted together. */
    static String groupName(FieldChange change) {
        String field = change.getField().replaceAll("items\\[\\d+]", "items[]");
        if (FieldChange.ADDED.equals(change.getChangeType())) {
            return field + " (line added)";
        }
        if (FieldChange.REMOVED.equals(change.getChangeType())) {
            return field + " (line removed)";
        }
        return field;
    }

    public long getSubmissions() { return submissions; }
    public long getFieldsCompared() { return fieldsCompared; }
    public long getFieldsChanged() { return fieldsChanged; }
    public Map<String, Long> getChangesByField() { return changesByField; }

    /** null when nothing was submitted in the period (no denominator - no number is shown). */
    public Double getRate() {
        return fieldsCompared == 0 ? null : (double) fieldsChanged / fieldsCompared;
    }
}
