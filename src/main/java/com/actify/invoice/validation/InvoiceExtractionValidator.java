package com.actify.invoice.validation;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.exception.InvoiceValidationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The LLM gives us structured data, but its values are still untrusted.
 * Structured output only guarantees the JSON shape - the model can still misread a number,
 * drop a line item or return a negative amount. Business validation happens here,
 * before anything is persisted.
 *
 * All problems are collected and reported together, so one 422 response shows everything wrong.
 *  - findReasons(): every problem with a machine-readable code (used to route new invoices to review);
 *  - findProblems(): the same problems as field errors (used by the edit form);
 *  - validate(): the original single-message behaviour of the legacy extraction flow.
 */
@Component
public class InvoiceExtractionValidator {

    // Rounding differences of up to one cent are normal on real invoices.
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final int MAX_TEXT_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    public void validate(InvoiceExtractionResult result) {
        List<FieldErrorDto> errors = findProblems(result);
        if (!errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            for (FieldErrorDto error : errors) {
                messages.add(error.getMessage());
            }
            throw new InvoiceValidationException(String.join("; ", messages));
        }
    }

    public List<FieldErrorDto> findProblems(InvoiceExtractionResult result) {
        List<FieldErrorDto> errors = new ArrayList<>();
        for (ReviewReason reason : findReasons(result)) {
            errors.add(new FieldErrorDto(reason.getField(), reason.getMessage()));
        }
        return errors;
    }

    public List<ReviewReason> findReasons(InvoiceExtractionResult result) {
        List<ReviewReason> errors = new ArrayList<>();

        // Without these two values the record is useless, so they are mandatory.
        if (isBlank(result.getInvoiceNumber())) {
            errors.add(new ReviewReason(ReviewReasonCode.MISSING_FIELD, "invoiceNumber", "invoiceNumber is missing"));
        } else {
            checkText("invoiceNumber", result.getInvoiceNumber(), MAX_TEXT_LENGTH, errors);
        }
        if (result.getTotalAmount() == null) {
            errors.add(new ReviewReason(ReviewReasonCode.MISSING_FIELD, "totalAmount", "totalAmount is missing"));
        }

        checkText("sellerName", result.getSellerName(), MAX_TEXT_LENGTH, errors);
        checkText("sellerTaxId", result.getSellerTaxId(), MAX_TEXT_LENGTH, errors);
        checkText("buyerName", result.getBuyerName(), MAX_TEXT_LENGTH, errors);
        checkText("buyerTaxId", result.getBuyerTaxId(), MAX_TEXT_LENGTH, errors);

        if (result.getInvoiceDate() != null) {
            try {
                LocalDate.parse(result.getInvoiceDate());
            } catch (DateTimeParseException e) {
                errors.add(new ReviewReason(ReviewReasonCode.INVALID_DATE, "invoiceDate",
                        "invoiceDate '" + result.getInvoiceDate() + "' is not a valid YYYY-MM-DD date"));
            }
        }

        if (result.getCurrency() != null && !result.getCurrency().matches("[A-Z]{3}")) {
            errors.add(new ReviewReason(ReviewReasonCode.INVALID_CURRENCY, "currency",
                    "currency '" + result.getCurrency() + "' is not a 3-letter ISO code (e.g. INR, USD)"));
        }

        checkNotNegative("subtotal", result.getSubtotal(), errors);
        checkNotNegative("discountAmount", result.getDiscountAmount(), errors);
        checkNotNegative("shippingAmount", result.getShippingAmount(), errors);
        checkNotNegative("taxAmount", result.getTaxAmount(), errors);
        checkNotNegative("totalAmount", result.getTotalAmount(), errors);

        validateItems(result.getItems(), errors);
        validateArithmetic(result, errors);
        return errors;
    }

    private void validateItems(List<InvoiceItemDto> items, List<ReviewReason> errors) {
        for (int i = 0; i < items.size(); i++) {
            InvoiceItemDto item = items.get(i);
            String prefix = "items[" + i + "].";

            checkText(prefix + "description", item.getDescription(), MAX_DESCRIPTION_LENGTH, errors);

            if (item.getQuantity() != null && item.getQuantity().signum() <= 0) {
                errors.add(new ReviewReason(ReviewReasonCode.INVALID_QUANTITY, prefix + "quantity",
                        prefix + "quantity must be greater than 0"));
            }
            checkNotNegative(prefix + "unitPrice", item.getUnitPrice(), errors);
            checkNotNegative(prefix + "taxRate", item.getTaxRate(), errors);
            checkNotNegative(prefix + "lineTotal", item.getLineTotal(), errors);
        }
    }

    /**
     * Cross-checks the totals. We only compare when the needed values are actually present -
     * a missing value is not the same as a wrong value.
     */
    private void validateArithmetic(InvoiceExtractionResult result, List<ReviewReason> errors) {
        List<InvoiceItemDto> items = result.getItems();

        if (result.getSubtotal() != null && !items.isEmpty()) {
            BigDecimal sumOfLines = BigDecimal.ZERO;
            boolean allLineTotalsPresent = true;
            for (InvoiceItemDto item : items) {
                if (item.getLineTotal() == null) {
                    allLineTotalsPresent = false;
                    break;
                }
                sumOfLines = sumOfLines.add(item.getLineTotal());
            }
            if (allLineTotalsPresent && !approximatelyEqual(sumOfLines, result.getSubtotal())) {
                errors.add(new ReviewReason(ReviewReasonCode.LINE_SUM_MISMATCH, "subtotal",
                        "sum of line totals (" + sumOfLines.toPlainString()
                                + ") does not match subtotal (" + result.getSubtotal().toPlainString() + ")"));
            }
        }

        // total = subtotal - discount + shipping + tax.
        // subtotal is the product lines BEFORE the discount (checked above against the line sum),
        // and discount/shipping are separate adjustments, so nothing is counted twice.
        // A null discount/shipping means "not on this invoice" and adds nothing; this keeps
        // invoices without these fields on the original subtotal + tax = total rule.
        if (result.getSubtotal() != null && result.getTaxAmount() != null && result.getTotalAmount() != null) {
            BigDecimal expectedTotal = result.getSubtotal();
            String formula = "subtotal";
            if (result.getDiscountAmount() != null) {
                expectedTotal = expectedTotal.subtract(result.getDiscountAmount());
                formula += " - discountAmount";
            }
            if (result.getShippingAmount() != null) {
                expectedTotal = expectedTotal.add(result.getShippingAmount());
                formula += " + shippingAmount";
            }
            expectedTotal = expectedTotal.add(result.getTaxAmount());
            formula += " + taxAmount";

            if (!approximatelyEqual(expectedTotal, result.getTotalAmount())) {
                errors.add(new ReviewReason(ReviewReasonCode.TOTAL_MISMATCH, "totalAmount", formula
                        + " (" + expectedTotal.toPlainString() + ") does not match totalAmount ("
                        + result.getTotalAmount().toPlainString() + ")"));
            }
        }
    }

    // BigDecimal.compareTo, never equals(): equals() treats 10.0 and 10.00 as different.
    private boolean approximatelyEqual(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    private void checkNotNegative(String field, BigDecimal value, List<ReviewReason> errors) {
        if (value != null && value.signum() < 0) {
            errors.add(new ReviewReason(ReviewReasonCode.NEGATIVE_AMOUNT, field, field + " must not be negative"));
        }
    }

    private void checkText(String field, String value, int maxLength, List<ReviewReason> errors) {
        if (value == null) {
            return;
        }
        if (value.trim().isEmpty()) {
            errors.add(new ReviewReason(ReviewReasonCode.INVALID_TEXT, field, field + " is blank"));
        } else if (value.length() > maxLength) {
            errors.add(new ReviewReason(ReviewReasonCode.INVALID_TEXT, field, field + " is longer than " + maxLength + " characters"));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
