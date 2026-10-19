package com.actify.invoice.validation;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies an extraction into the invoice columns for HUMAN REVIEW (batch workflow), even when it has problems.
 *
 * Rule: a value is stored only if its column can hold it exactly. Otherwise the column stays empty (null),
 * nothing is truncated or silently rounded, and the full original answer stays in invoices.raw_llm_response.
 *  - text that is blank / too long, an unparsable date, a non-ISO currency -> null (the validator reports why);
 *  - a number with too many digits or decimals for its numeric(p,s) column -> null + AMOUNT_OUT_OF_RANGE / AMOUNT_PRECISION.
 * (The legacy /extract flow is unchanged: it still rejects invalid extractions instead of storing them.)
 */
public final class StorableValues {

    static final int MAX_TEXT_LENGTH = 255;
    static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_EDITABLE_ITEMS = 200;

    // numeric(precision, scale) of the target columns
    private static final int MONEY_SCALE = 2;
    private static final int MONEY_INTEGER_DIGITS = 17;      // numeric(19,2)
    private static final int QUANTITY_SCALE = 3;
    private static final int QUANTITY_INTEGER_DIGITS = 16;   // numeric(19,3)
    private static final int TAX_RATE_SCALE = 3;
    private static final int TAX_RATE_INTEGER_DIGITS = 4;    // numeric(7,3)

    private StorableValues() {
    }

    /** Fills the invoice's data columns and items; returns the problems that only this step can see. */
    public static List<ReviewReason> copyInto(InvoiceExtractionResult r, Invoice invoice) {
        List<ReviewReason> reasons = new ArrayList<>();
        invoice.setInvoiceNumber(text(r.getInvoiceNumber(), MAX_TEXT_LENGTH));
        invoice.setInvoiceDate(date(r.getInvoiceDate()));
        invoice.setSellerName(text(r.getSellerName(), MAX_TEXT_LENGTH));
        invoice.setSellerTaxId(text(r.getSellerTaxId(), MAX_TEXT_LENGTH));
        invoice.setBuyerName(text(r.getBuyerName(), MAX_TEXT_LENGTH));
        invoice.setBuyerTaxId(text(r.getBuyerTaxId(), MAX_TEXT_LENGTH));
        invoice.setCurrency(r.getCurrency() != null && r.getCurrency().matches("[A-Z]{3}") ? r.getCurrency() : null);
        invoice.setSubtotal(number("subtotal", r.getSubtotal(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
        invoice.setDiscountAmount(number("discountAmount", r.getDiscountAmount(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
        invoice.setShippingAmount(number("shippingAmount", r.getShippingAmount(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
        invoice.setTaxAmount(number("taxAmount", r.getTaxAmount(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
        invoice.setTotalAmount(number("totalAmount", r.getTotalAmount(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));

        List<InvoiceItemDto> items = r.getItems();
        for (int i = 0; i < items.size(); i++) {
            InvoiceItemDto dto = items.get(i);
            String p = "items[" + i + "].";
            InvoiceItem item = new InvoiceItem();
            item.setDescription(text(dto.getDescription(), MAX_DESCRIPTION_LENGTH));
            item.setQuantity(number(p + "quantity", dto.getQuantity(), QUANTITY_SCALE, QUANTITY_INTEGER_DIGITS, reasons));
            item.setUnitPrice(number(p + "unitPrice", dto.getUnitPrice(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
            item.setTaxRate(number(p + "taxRate", dto.getTaxRate(), TAX_RATE_SCALE, TAX_RATE_INTEGER_DIGITS, reasons));
            item.setLineTotal(number(p + "lineTotal", dto.getLineTotal(), MONEY_SCALE, MONEY_INTEGER_DIGITS, reasons));
            invoice.addItem(item);
        }
        if (items.size() > MAX_EDITABLE_ITEMS) {
            reasons.add(new ReviewReason(ReviewReasonCode.TOO_MANY_ITEMS, "items", items.size()
                    + " line items were extracted; the correction form accepts at most " + MAX_EDITABLE_ITEMS));
        }
        return reasons;
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || value.length() > maxLength ? null : trimmed;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal number(String field, BigDecimal value, int maxScale, int maxIntegerDigits,
                                     List<ReviewReason> reasons) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();   // "10.500" has 2 real decimals, not 3
        if (stripped.scale() > maxScale) {
            reasons.add(new ReviewReason(ReviewReasonCode.AMOUNT_PRECISION, field, field + " " + value.toPlainString()
                    + " has more than " + maxScale + " decimal places; not stored (see the original extraction)"));
            return null;
        }
        if (stripped.precision() - stripped.scale() > maxIntegerDigits) {
            reasons.add(new ReviewReason(ReviewReasonCode.AMOUNT_OUT_OF_RANGE, field, field + " " + value.toPlainString()
                    + " is too large to store; not stored (see the original extraction)"));
            return null;
        }
        // Only pads zeros: the checks above guarantee no rounding happens.
        return value.setScale(maxScale, RoundingMode.UNNECESSARY);
    }
}
