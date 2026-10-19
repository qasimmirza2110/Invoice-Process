package com.actify.invoice.versioning;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds snapshots, converts them to/from JSON and compares two of them. */
@Component
public class InvoiceSnapshotMapper {

    // Same scales as the invoices / invoice_items columns.
    public static final int MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 3;
    public static final int TAX_RATE_SCALE = 3;

    private final ObjectMapper objectMapper;

    public InvoiceSnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Snapshot of what is in the entity. Call it on a freshly flushed + refreshed entity to capture what PostgreSQL stored. */
    public InvoiceSnapshot fromEntity(Invoice invoice) {
        InvoiceSnapshot s = header(invoice.getInvoiceNumber(),
                invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString(),
                invoice.getSellerName(), invoice.getSellerTaxId(), invoice.getBuyerName(), invoice.getBuyerTaxId(),
                invoice.getCurrency(), invoice.getSubtotal(), invoice.getDiscountAmount(), invoice.getShippingAmount(),
                invoice.getTaxAmount(), invoice.getTotalAmount());
        int lineNo = 1;
        for (InvoiceItem item : invoice.getItems()) {
            s.getItems().add(item(lineNo++, item.getDescription(), item.getQuantity(), item.getUnitPrice(),
                    item.getTaxRate(), item.getLineTotal()));
        }
        return s;
    }

    /** Snapshot of already validated + normalized values (used to detect a no-change save). */
    public InvoiceSnapshot fromValues(InvoiceExtractionResult values) {
        InvoiceSnapshot s = header(values.getInvoiceNumber(), values.getInvoiceDate(), values.getSellerName(),
                values.getSellerTaxId(), values.getBuyerName(), values.getBuyerTaxId(), values.getCurrency(),
                values.getSubtotal(), values.getDiscountAmount(), values.getShippingAmount(), values.getTaxAmount(),
                values.getTotalAmount());
        int lineNo = 1;
        for (InvoiceItemDto item : values.getItems()) {
            s.getItems().add(item(lineNo++, item.getDescription(), item.getQuantity(), item.getUnitPrice(),
                    item.getTaxRate(), item.getLineTotal()));
        }
        return s;
    }

    private InvoiceSnapshot header(String invoiceNumber, String invoiceDate, String sellerName, String sellerTaxId,
                                   String buyerName, String buyerTaxId, String currency, BigDecimal subtotal,
                                   BigDecimal discount, BigDecimal shipping, BigDecimal tax, BigDecimal total) {
        InvoiceSnapshot s = new InvoiceSnapshot();
        s.setInvoiceNumber(invoiceNumber);
        s.setInvoiceDate(invoiceDate);
        s.setSellerName(sellerName);
        s.setSellerTaxId(sellerTaxId);
        s.setBuyerName(buyerName);
        s.setBuyerTaxId(buyerTaxId);
        s.setCurrency(currency);
        s.setSubtotal(decimal(subtotal, MONEY_SCALE));
        s.setDiscountAmount(decimal(discount, MONEY_SCALE));
        s.setShippingAmount(decimal(shipping, MONEY_SCALE));
        s.setTaxAmount(decimal(tax, MONEY_SCALE));
        s.setTotalAmount(decimal(total, MONEY_SCALE));
        return s;
    }

    private InvoiceSnapshot.Item item(int lineNo, String description, BigDecimal quantity, BigDecimal unitPrice,
                                      BigDecimal taxRate, BigDecimal lineTotal) {
        InvoiceSnapshot.Item i = new InvoiceSnapshot.Item();
        i.setLineNo(lineNo);
        i.setDescription(description);
        i.setQuantity(decimal(quantity, QUANTITY_SCALE));
        i.setUnitPrice(decimal(unitPrice, MONEY_SCALE));
        i.setTaxRate(decimal(taxRate, TAX_RATE_SCALE));
        i.setLineTotal(decimal(lineTotal, MONEY_SCALE));
        return i;
    }

    // Values from the database already have this scale; edits are rejected before this point if they
    // have more decimals, so HALF_UP here only pads zeros ("10.5" -> "10.50"), it never rounds real data.
    private String decimal(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize invoice snapshot", e);
        }
    }

    public InvoiceSnapshot snapshotFromJson(String json) {
        try {
            return objectMapper.readValue(json, InvoiceSnapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("Stored invoice snapshot is not readable", e);
        }
    }

    public List<FieldChange> changesFromJson(String json) {
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldChange>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Stored changed-field list is not readable", e);
        }
    }

    /** Every field that differs, header first, then line items by position. Empty = no change. */
    public List<FieldChange> diff(InvoiceSnapshot from, InvoiceSnapshot to) {
        List<FieldChange> changes = new ArrayList<>();
        compare("invoiceNumber", from.getInvoiceNumber(), to.getInvoiceNumber(), changes);
        compare("invoiceDate", from.getInvoiceDate(), to.getInvoiceDate(), changes);
        compare("sellerName", from.getSellerName(), to.getSellerName(), changes);
        compare("sellerTaxId", from.getSellerTaxId(), to.getSellerTaxId(), changes);
        compare("buyerName", from.getBuyerName(), to.getBuyerName(), changes);
        compare("buyerTaxId", from.getBuyerTaxId(), to.getBuyerTaxId(), changes);
        compare("currency", from.getCurrency(), to.getCurrency(), changes);
        compare("subtotal", from.getSubtotal(), to.getSubtotal(), changes);
        compare("discountAmount", from.getDiscountAmount(), to.getDiscountAmount(), changes);
        compare("shippingAmount", from.getShippingAmount(), to.getShippingAmount(), changes);
        compare("taxAmount", from.getTaxAmount(), to.getTaxAmount(), changes);
        compare("totalAmount", from.getTotalAmount(), to.getTotalAmount(), changes);

        List<InvoiceSnapshot.Item> a = from.getItems();
        List<InvoiceSnapshot.Item> b = to.getItems();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String prefix = "items[" + i + "]";
            if (i >= a.size()) {
                changes.add(new FieldChange(prefix, FieldChange.ADDED, null, summary(b.get(i))));
            } else if (i >= b.size()) {
                changes.add(new FieldChange(prefix, FieldChange.REMOVED, summary(a.get(i)), null));
            } else {
                InvoiceSnapshot.Item x = a.get(i);
                InvoiceSnapshot.Item y = b.get(i);
                compare(prefix + ".description", x.getDescription(), y.getDescription(), changes);
                compare(prefix + ".quantity", x.getQuantity(), y.getQuantity(), changes);
                compare(prefix + ".unitPrice", x.getUnitPrice(), y.getUnitPrice(), changes);
                compare(prefix + ".taxRate", x.getTaxRate(), y.getTaxRate(), changes);
                compare(prefix + ".lineTotal", x.getLineTotal(), y.getLineTotal(), changes);
            }
        }
        return changes;
    }

    private void compare(String field, String from, String to, List<FieldChange> changes) {
        if (!Objects.equals(from, to)) {
            changes.add(new FieldChange(field, FieldChange.CHANGED, from, to));
        }
    }

    private String summary(InvoiceSnapshot.Item item) {
        return (item.getDescription() == null ? "(no description)" : item.getDescription())
                + " | qty " + item.getQuantity() + " x " + item.getUnitPrice() + " = " + item.getLineTotal();
    }
}
