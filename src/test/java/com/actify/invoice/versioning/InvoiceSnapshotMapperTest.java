package com.actify.invoice.versioning;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version snapshots: every business field and item is captured in order, JSON round trips are lossless, numeric
 * scale differences are not reported as changes, and the field-level diff reports header and item changes correctly.
 */
class InvoiceSnapshotMapperTest {

    private final InvoiceSnapshotMapper mapper = new InvoiceSnapshotMapper(new ObjectMapper());

    private Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.setId(7L);
        invoice.setInvoiceNumber("000000005");
        invoice.setInvoiceDate(LocalDate.of(2026, 10, 6));
        invoice.setSellerName("Seller");
        invoice.setSellerTaxId("6000000001");
        invoice.setBuyerName("Buyer");
        invoice.setBuyerTaxId("0012");
        invoice.setCurrency("INR");
        invoice.setSubtotal(new BigDecimal("10500.00"));
        invoice.setDiscountAmount(null);
        invoice.setShippingAmount(new BigDecimal("100.5"));
        invoice.setTaxAmount(new BigDecimal("1890.00"));
        invoice.setTotalAmount(new BigDecimal("12490.50"));
        invoice.setRawLlmResponse("{\"secret\":\"RAW-LLM\"}");
        invoice.setGeminiModelId("model-x");
        invoice.addItem(item(1L, "First", "2", "5000.00", "18", "10000.00"));
        invoice.addItem(item(2L, "Second", "1", "500.00", null, "500.00"));
        return invoice;
    }

    private InvoiceItem item(Long id, String description, String qty, String price, String rate, String total) {
        InvoiceItem item = new InvoiceItem();
        item.setId(id);
        item.setDescription(description);
        item.setQuantity(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setTaxRate(rate == null ? null : new BigDecimal(rate));
        item.setLineTotal(new BigDecimal(total));
        return item;
    }

    @Test
    void snapshotHasEveryBusinessFieldAndEveryItemInOrder() throws Exception {
        InvoiceSnapshot s = mapper.fromEntity(invoice());

        assertThat(s.getSchemaVersion()).isEqualTo(1);
        assertThat(s.getInvoiceNumber()).isEqualTo("000000005");
        assertThat(s.getSellerTaxId()).isEqualTo("6000000001");
        assertThat(s.getBuyerTaxId()).isEqualTo("0012");
        assertThat(s.getInvoiceDate()).isEqualTo("2026-10-06");
        assertThat(s.getDiscountAmount()).isNull();                 // null stays null, never 0
        assertThat(s.getShippingAmount()).isEqualTo("100.50");      // database scale
        assertThat(s.getTotalAmount()).isEqualTo("12490.50");
        assertThat(s.getItems()).extracting(InvoiceSnapshot.Item::getLineNo).containsExactly(1, 2);
        assertThat(s.getItems()).extracting(InvoiceSnapshot.Item::getDescription).containsExactly("First", "Second");
        assertThat(s.getItems().get(0).getQuantity()).isEqualTo("2.000");
        assertThat(s.getItems().get(0).getTaxRate()).isEqualTo("18.000");
        assertThat(s.getItems().get(1).getTaxRate()).isNull();

        // Exact key set: business fields only - no ids, raw LLM text, token metrics or JPA internals.
        JsonNode json = new ObjectMapper().readTree(mapper.toJson(s));
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        assertThat(keys).containsExactly("schemaVersion", "invoiceNumber", "invoiceDate", "sellerName", "sellerTaxId",
                "buyerName", "buyerTaxId", "currency", "subtotal", "discountAmount", "shippingAmount", "taxAmount",
                "totalAmount", "items");
        List<String> itemKeys = new ArrayList<>();
        for (Iterator<String> it = json.get("items").get(0).fieldNames(); it.hasNext(); ) {
            itemKeys.add(it.next());
        }
        assertThat(itemKeys).containsExactly("lineNo", "description", "quantity", "unitPrice", "taxRate", "lineTotal");
        assertThat(json.toString()).doesNotContain("RAW-LLM").doesNotContain("model-x");
        // Amounts and identifiers are JSON strings, so nothing can turn them into floats or strip zeros.
        assertThat(json.get("invoiceNumber").isTextual()).isTrue();
        assertThat(json.get("totalAmount").isTextual()).isTrue();
    }

    @Test
    void jsonRoundTripIsLossless() {
        InvoiceSnapshot original = mapper.fromEntity(invoice());
        InvoiceSnapshot back = mapper.snapshotFromJson(mapper.toJson(original));
        assertThat(mapper.diff(original, back)).isEmpty();
        assertThat(back.getInvoiceNumber()).isEqualTo("000000005");
        assertThat(back.getItems()).hasSize(2);
    }

    @Test
    void sameValuesWithDifferentScaleAreNoChange() {
        Invoice a = invoice();
        Invoice b = invoice();
        b.setSubtotal(new BigDecimal("10500"));          // 10500 == 10500.00
        b.setShippingAmount(new BigDecimal("100.500"));
        assertThat(mapper.diff(mapper.fromEntity(a), mapper.fromEntity(b))).isEmpty();
    }

    @Test
    void diffReportsHeaderItemAddedAndRemovedChanges() {
        Invoice before = invoice();
        Invoice after = invoice();
        after.setInvoiceNumber("00000005");                  // a dropped leading zero IS a change
        after.setDiscountAmount(new BigDecimal("10.00"));    // null -> value
        after.getItems().get(1).setQuantity(new BigDecimal("3"));
        after.addItem(item(3L, "Third", "1", "1.00", "0", "1.00"));

        List<FieldChange> changes = mapper.diff(mapper.fromEntity(before), mapper.fromEntity(after));

        assertThat(changes).extracting(FieldChange::getField)
                .containsExactly("invoiceNumber", "discountAmount", "items[1].quantity", "items[2]");
        assertThat(changes.get(0).getFrom()).isEqualTo("000000005");
        assertThat(changes.get(0).getTo()).isEqualTo("00000005");
        assertThat(changes.get(1).getFrom()).isNull();
        assertThat(changes.get(3).getChangeType()).isEqualTo(FieldChange.ADDED);
        assertThat(changes.get(3).getTo()).contains("Third");

        List<FieldChange> back = mapper.diff(mapper.fromEntity(after), mapper.fromEntity(before));
        assertThat(back.get(back.size() - 1).getChangeType()).isEqualTo(FieldChange.REMOVED);
    }

    @Test
    void changedFieldListRoundTripsThroughJson() {
        List<FieldChange> changes = mapper.diff(mapper.fromEntity(invoice()), mapper.fromEntity(new Invoice()));
        List<FieldChange> back = mapper.changesFromJson(mapper.toJson(changes));
        assertThat(back).hasSameSizeAs(changes);
        assertThat(back.get(0).getField()).isEqualTo(changes.get(0).getField());
        assertThat(mapper.changesFromJson(null)).isEmpty();
    }
}
