package com.actify.invoice.versioning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete business content of one invoice version, stored as JSONB in invoice_versions.snapshot_data.
 * It is built field by field from the entity (never by serializing the Hibernate graph), so it has no
 * lazy proxies, database ids, raw LLM text or token metrics - only what is needed to rebuild the invoice.
 *
 * Amounts are decimal STRINGS at the database scale ("10000.00", quantity "2.000"), so trailing
 * zeros and precision survive JSON exactly. Identifiers are strings, so leading zeros survive too.
 * Line items keep their order; lineNo is the 1-based position.
 */
@JsonPropertyOrder({"schemaVersion", "invoiceNumber", "invoiceDate", "sellerName", "sellerTaxId", "buyerName",
        "buyerTaxId", "currency", "subtotal", "discountAmount", "shippingAmount", "taxAmount", "totalAmount", "items"})
public class InvoiceSnapshot {

    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private String invoiceNumber;
    private String invoiceDate;          // YYYY-MM-DD or null
    private String sellerName;
    private String sellerTaxId;
    private String buyerName;
    private String buyerTaxId;
    private String currency;
    private String subtotal;
    private String discountAmount;
    private String shippingAmount;
    private String taxAmount;
    private String totalAmount;
    private List<Item> items = new ArrayList<>();

    @JsonPropertyOrder({"lineNo", "description", "quantity", "unitPrice", "taxRate", "lineTotal"})
    public static class Item {
        private int lineNo;
        private String description;
        private String quantity;
        private String unitPrice;
        private String taxRate;
        private String lineTotal;

        public int getLineNo() { return lineNo; }
        public void setLineNo(int lineNo) { this.lineNo = lineNo; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity; }

        public String getUnitPrice() { return unitPrice; }
        public void setUnitPrice(String unitPrice) { this.unitPrice = unitPrice; }

        public String getTaxRate() { return taxRate; }
        public void setTaxRate(String taxRate) { this.taxRate = taxRate; }

        public String getLineTotal() { return lineTotal; }
        public void setLineTotal(String lineTotal) { this.lineTotal = lineTotal; }
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(String invoiceDate) { this.invoiceDate = invoiceDate; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public String getSellerTaxId() { return sellerTaxId; }
    public void setSellerTaxId(String sellerTaxId) { this.sellerTaxId = sellerTaxId; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getBuyerTaxId() { return buyerTaxId; }
    public void setBuyerTaxId(String buyerTaxId) { this.buyerTaxId = buyerTaxId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSubtotal() { return subtotal; }
    public void setSubtotal(String subtotal) { this.subtotal = subtotal; }

    public String getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(String discountAmount) { this.discountAmount = discountAmount; }

    public String getShippingAmount() { return shippingAmount; }
    public void setShippingAmount(String shippingAmount) { this.shippingAmount = shippingAmount; }

    public String getTaxAmount() { return taxAmount; }
    public void setTaxAmount(String taxAmount) { this.taxAmount = taxAmount; }

    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items != null ? items : new ArrayList<Item>(); }
}
