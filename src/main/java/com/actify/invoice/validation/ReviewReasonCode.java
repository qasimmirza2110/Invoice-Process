package com.actify.invoice.validation;

/**
 * Machine-readable reasons why an extracted invoice needs a human's attention.
 * Stored in invoice_review_reasons.code (max 40 characters) and counted on the dashboards.
 * These are deterministic checks - NOT a model "confidence" or an accuracy score.
 */
public enum ReviewReasonCode {
    /** invoiceNumber or totalAmount is missing. */
    MISSING_FIELD,
    /** A text value is blank or too long for its column. */
    INVALID_TEXT,
    /** invoiceDate is not a valid YYYY-MM-DD date. */
    INVALID_DATE,
    /** currency is not a 3-letter ISO code. */
    INVALID_CURRENCY,
    /** An amount is negative. */
    NEGATIVE_AMOUNT,
    /** A line item quantity is zero or negative. */
    INVALID_QUANTITY,
    /** The line totals do not add up to the subtotal. */
    LINE_SUM_MISMATCH,
    /** subtotal - discount + shipping + tax does not equal the total. */
    TOTAL_MISMATCH,
    /** A number has more digits than its database column can hold; it was not stored (raw JSON kept). */
    AMOUNT_OUT_OF_RANGE,
    /** A number has more decimal places than its column; it was not rounded and not stored (raw JSON kept). */
    AMOUNT_PRECISION,
    /** More line items than the correction form accepts (200). */
    TOO_MANY_ITEMS,
    /** The same PDF bytes (SHA-256) were already uploaded by another vendor. */
    DUPLICATE_FILE,
    /** Another invoice has the same seller name and invoice number. */
    DUPLICATE_INVOICE_NO
}
