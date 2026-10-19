package com.actify.invoice.entity;

/** Where the data of one invoice version came from. */
public enum VersionChangeSource {
    /** V1 of a new invoice: the validated LLM extraction, exactly as first stored. */
    LLM_EXTRACTION,
    /** A correction saved by a user in the review form. */
    MANUAL_EDIT,
    /**
     * The pre-edit state of an invoice created before version history existed, captured on
     * its first correction. It is NOT a verified copy of the original LLM extraction.
     */
    LEGACY_BASELINE
}
