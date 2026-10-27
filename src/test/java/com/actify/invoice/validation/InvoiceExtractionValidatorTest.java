package com.actify.invoice.validation;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.exception.InvoiceValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deterministic arithmetic and format checks applied to every extraction: totals, line totals, shipping and
 * discount handling, negative values, missing critical fields, the one-cent rounding tolerance, and that every problem
 * carries a machine-readable reason code.
 */
class InvoiceExtractionValidatorTest {

    private final InvoiceExtractionValidator validator = new InvoiceExtractionValidator();

    /** 2 x 5000 + 1 x 500 = 10500 subtotal, 18% tax = 1890, total 12390. */
    static InvoiceExtractionResult validInvoice() {
        InvoiceExtractionResult result = new InvoiceExtractionResult();
        result.setInvoiceNumber("INV-1001");
        result.setInvoiceDate("2026-10-06");
        result.setSellerName("ABC Technologies Pvt Ltd");
        result.setSellerTaxId("GSTIN123");
        result.setBuyerName("XYZ Solutions");
        result.setBuyerTaxId("GSTIN456");
        result.setCurrency("INR");
        result.setSubtotal(new BigDecimal("10500.00"));
        result.setTaxAmount(new BigDecimal("1890.00"));
        result.setTotalAmount(new BigDecimal("12390.00"));
        result.setItems(Arrays.asList(
                new InvoiceItemDto("Laptop Stand", new BigDecimal("2"), new BigDecimal("5000.00"),
                        new BigDecimal("18"), new BigDecimal("10000.00")),
                new InvoiceItemDto("USB Cable", new BigDecimal("1"), new BigDecimal("500.00"),
                        new BigDecimal("18"), new BigDecimal("500.00"))));
        return result;
    }

    @Test
    void acceptsValidInvoice() {
        assertThatCode(() -> validator.validate(validInvoice())).doesNotThrowAnyException();
    }

    @Test
    void everyProblemHasAMachineReadableReasonCodeAndTheSameMessageAsBefore() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setInvoiceNumber(" ");
        invoice.setInvoiceDate("2026-13-40");
        invoice.setCurrency("rupees");
        invoice.setTotalAmount(new BigDecimal("1.00"));

        java.util.List<ReviewReason> reasons = validator.findReasons(invoice);

        org.assertj.core.api.Assertions.assertThat(reasons).extracting(ReviewReason::getCode).containsExactly(
                ReviewReasonCode.MISSING_FIELD, ReviewReasonCode.INVALID_DATE, ReviewReasonCode.INVALID_CURRENCY,
                ReviewReasonCode.TOTAL_MISMATCH);
        org.assertj.core.api.Assertions.assertThat(validator.findProblems(invoice)).extracting("message")
                .containsExactlyElementsOf(reasons.stream().map(ReviewReason::getMessage)
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void acceptsOneCentRoundingDifference() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setTotalAmount(new BigDecimal("12390.01"));

        assertThatCode(() -> validator.validate(invoice)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTotalMismatch() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setTotalAmount(new BigDecimal("13000.00"));

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("does not match totalAmount");
    }

    @Test
    void rejectsLineTotalsNotMatchingSubtotal() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.getItems().get(1).setLineTotal(new BigDecimal("900.00"));

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("does not match subtotal");
    }

    @Test
    void rejectsNegativeValues() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setTaxAmount(new BigDecimal("-1890.00"));
        invoice.getItems().get(0).setQuantity(new BigDecimal("-2"));

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("taxAmount must not be negative")
                .hasMessageContaining("items[0].quantity must be greater than 0");
    }

    @Test
    void rejectsMissingCriticalFieldsAndBadFormats() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setInvoiceNumber(null);
        invoice.setInvoiceDate("06/10/2026");
        invoice.setCurrency("Rs");

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("invoiceNumber is missing")
                .hasMessageContaining("not a valid YYYY-MM-DD date")
                .hasMessageContaining("not a 3-letter ISO code");
    }

    // ---------------- shipping and discount (amount patterns of real-format sample invoices) ----------------

    private static InvoiceItemDto line(String description, String quantity, String lineTotal) {
        // These invoices print quantity and line subtotal only - no unit price or tax rate per line.
        return new InvoiceItemDto(description, new BigDecimal(quantity), null, null, new BigDecimal(lineTotal));
    }

    private static InvoiceExtractionResult usdInvoice(String number, String subtotal, String discount, String shipping,
                                                      String tax, String total, InvoiceItemDto... items) {
        InvoiceExtractionResult result = new InvoiceExtractionResult();
        result.setInvoiceNumber(number);
        result.setCurrency("USD");
        result.setSubtotal(new BigDecimal(subtotal));
        result.setDiscountAmount(discount == null ? null : new BigDecimal(discount));
        result.setShippingAmount(shipping == null ? null : new BigDecimal(shipping));
        result.setTaxAmount(new BigDecimal(tax));
        result.setTotalAmount(new BigDecimal(total));
        result.setItems(Arrays.asList(items));
        return result;
    }

    /** Sample layout B: 93.00 + 10.00 shipping + 7.67 tax = 110.67. */
    static InvoiceExtractionResult shippingOnlyInvoice() {
        return usdInvoice("#6000000001", "93.00", null, "10.00", "7.67", "110.67",
                line("Desk Lamp", "1", "43.00"), line("Canvas Bag", "1", "50.00"));
    }

    /** Sample layout A: 220.00 - 18.20 + 25.00 + 16.65 = 243.45. */
    static InvoiceExtractionResult shippingAndDiscountInvoice() {
        return usdInvoice("#000000005", "220.00", "18.20", "25.00", "16.65", "243.45",
                line("Desk Lamp 7\" Shade", "3", "129.00"), line("Canvas Bag", "1", "59.00"),
                line("Cotton T-Shirt", "1", "32.00"));
    }

    @Test
    void acceptsShippingWithoutDiscount() {
        assertThatCode(() -> validator.validate(shippingOnlyInvoice())).doesNotThrowAnyException();
    }

    @Test
    void acceptsDiscountWithoutShipping() {
        // 100.00 - 10.00 discount + 7.43 tax (8.25% of 90.00) = 97.43
        InvoiceExtractionResult invoice = usdInvoice("D-1", "100.00", "10.00", null, "7.43", "97.43",
                line("Widget", "2", "100.00"));

        assertThatCode(() -> validator.validate(invoice)).doesNotThrowAnyException();
    }

    @Test
    void acceptsShippingAndDiscount() {
        assertThatCode(() -> validator.validate(shippingAndDiscountInvoice())).doesNotThrowAnyException();

        // Sample layout C: 141.00 - 14.10 + 25.00 + 10.47 = 162.37
        InvoiceExtractionResult second = usdInvoice("#9000000001", "141.00", "14.10", "25.00", "10.47", "162.37",
                line("Wall Clock", "1", "49.00"), line("Canvas Bag", "1", "50.00"),
                line("Water Bottle", "1", "7.00"), line("Resistance Band", "1", "16.00"),
                line("Pushup Grips", "1", "19.00"));
        assertThatCode(() -> validator.validate(second)).doesNotThrowAnyException();
    }

    @Test
    void printedZeroAdjustmentsBehaveLikeMissingOnes() {
        InvoiceExtractionResult invoice = validInvoice();
        invoice.setShippingAmount(BigDecimal.ZERO);
        invoice.setDiscountAmount(new BigDecimal("0.00"));

        assertThatCode(() -> validator.validate(invoice)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongGrandTotalWhenAdjustmentsArePresent() {
        InvoiceExtractionResult invoice = shippingAndDiscountInvoice();
        invoice.setTotalAmount(new BigDecimal("279.85")); // discount added instead of subtracted

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("subtotal - discountAmount + shippingAmount + taxAmount (243.45)")
                .hasMessageContaining("does not match totalAmount (279.85)");
    }

    @Test
    void rejectsInvoiceWhoseShippingWasNotExtracted() {
        // The model must not leave out a printed charge: the old formula still fails loudly.
        InvoiceExtractionResult invoice = shippingOnlyInvoice();
        invoice.setShippingAmount(null);

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("subtotal + taxAmount (100.67) does not match totalAmount (110.67)");
    }

    @Test
    void rejectsShippingCountedAsALineItemAndAsShipping() {
        InvoiceExtractionResult invoice = shippingOnlyInvoice();
        invoice.setItems(Arrays.asList(line("Desk Lamp", "1", "43.00"), line("Canvas Bag", "1", "50.00"),
                line("Shipping & Handling", "1", "10.00")));

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("sum of line totals (103.00) does not match subtotal (93.00)");
    }

    @Test
    void rejectsNegativeDiscountAndShipping() {
        InvoiceExtractionResult invoice = shippingAndDiscountInvoice();
        invoice.setDiscountAmount(new BigDecimal("-18.20"));
        invoice.setShippingAmount(new BigDecimal("-25.00"));

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("discountAmount must not be negative")
                .hasMessageContaining("shippingAmount must not be negative");
    }

    @Test
    void stillRejectsMissingTotalEvenWithAdjustments() {
        InvoiceExtractionResult invoice = shippingAndDiscountInvoice();
        invoice.setTotalAmount(null);

        assertThatThrownBy(() -> validator.validate(invoice))
                .isInstanceOf(InvoiceValidationException.class)
                .hasMessageContaining("totalAmount is missing");
    }
}
