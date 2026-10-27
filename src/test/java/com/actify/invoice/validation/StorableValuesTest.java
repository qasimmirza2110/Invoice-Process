package com.actify.invoice.validation;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.entity.Invoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Values that do not fit the database columns are left empty and flagged - never silently truncated or rounded - and
 * more line items than the correction form accepts are still stored but flagged for review.
 */
class StorableValuesTest {

    @Test
    void validValuesAreStoredExactly() {
        InvoiceExtractionResult r = base();
        Invoice invoice = new Invoice();

        List<ReviewReason> reasons = StorableValues.copyInto(r, invoice);

        assertThat(reasons).isEmpty();
        assertThat(invoice.getInvoiceNumber()).isEqualTo("000123");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(invoice.getCurrency()).isEqualTo("INR");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("118.00");
        assertThat(invoice.getTotalAmount().scale()).isEqualTo(2);
        assertThat(invoice.getItems()).hasSize(1);
        assertThat(invoice.getItems().get(0).getQuantity()).isEqualByComparingTo("2.5");
    }

    @Test
    void unrepresentableValuesAreLeftEmptyNeverTruncatedOrRounded() {
        InvoiceExtractionResult r = base();
        r.setInvoiceDate("06/10/2026");                        // not ISO
        r.setCurrency("Rupees");                               // not ISO 4217
        r.setSellerName(repeat("x", 300));                     // longer than the column
        r.setTaxAmount(new BigDecimal("18.005"));              // 3 decimals in a numeric(19,2) column
        r.setSubtotal(new BigDecimal("123456789012345678.00")); // 18 integer digits > 17
        Invoice invoice = new Invoice();

        List<ReviewReason> reasons = StorableValues.copyInto(r, invoice);

        assertThat(invoice.getInvoiceDate()).isNull();
        assertThat(invoice.getCurrency()).isNull();
        assertThat(invoice.getSellerName()).isNull();
        assertThat(invoice.getTaxAmount()).isNull();
        assertThat(invoice.getSubtotal()).isNull();
        assertThat(reasons).extracting(ReviewReason::getCode)
                .containsExactly(ReviewReasonCode.AMOUNT_OUT_OF_RANGE, ReviewReasonCode.AMOUNT_PRECISION);
        assertThat(reasons).extracting(ReviewReason::getField).containsExactly("subtotal", "taxAmount");
    }

    @Test
    void trailingZerosDoNotCountAsExtraDecimals() {
        InvoiceExtractionResult r = base();
        r.setTotalAmount(new BigDecimal("118.0000"));
        Invoice invoice = new Invoice();

        assertThat(StorableValues.copyInto(r, invoice)).isEmpty();
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("118.00");
    }

    @Test
    void moreItemsThanTheFormAcceptsAreAllStoredButFlagged() {
        InvoiceExtractionResult r = base();
        List<InvoiceItemDto> items = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            items.add(new InvoiceItemDto("Line " + i, BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE));
        }
        r.setItems(items);
        Invoice invoice = new Invoice();

        List<ReviewReason> reasons = StorableValues.copyInto(r, invoice);

        assertThat(invoice.getItems()).hasSize(201);
        assertThat(reasons).extracting(ReviewReason::getCode).containsExactly(ReviewReasonCode.TOO_MANY_ITEMS);
    }

    private static InvoiceExtractionResult base() {
        InvoiceExtractionResult r = new InvoiceExtractionResult();
        r.setInvoiceNumber("000123");
        r.setInvoiceDate("2026-10-06");
        r.setSellerName("Synthetic Seller");
        r.setCurrency("INR");
        r.setSubtotal(new BigDecimal("100"));
        r.setTaxAmount(new BigDecimal("18"));
        r.setTotalAmount(new BigDecimal("118.00"));
        r.setItems(new ArrayList<>(Collections.singletonList(new InvoiceItemDto("Item", new BigDecimal("2.500"),
                new BigDecimal("40.00"), new BigDecimal("18"), new BigDecimal("100.00")))));
        return r;
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
