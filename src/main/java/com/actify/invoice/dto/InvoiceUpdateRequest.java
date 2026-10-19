package com.actify.invoice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body of PUT /api/invoices/{id}: the COMPLETE corrected invoice (every header field and the full
 * line-item list, in order) plus the version the user started from.
 *
 * This is an allowlist. id, status, versionNumber, createdAt, changedBy, source document, raw LLM
 * response, token usage... are not fields here, and sending them is rejected (400), not ignored.
 * Amounts may be sent as JSON strings ("1250.50") so the browser never turns them into floats.
 */
public class InvoiceUpdateRequest {

    private Integer baseVersion;
    private String changeReason;

    private String invoiceNumber;
    private String invoiceDate;          // YYYY-MM-DD, checked by the validator
    private String sellerName;
    private String sellerTaxId;
    private String buyerName;
    private String buyerTaxId;
    private String currency;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal shippingAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private List<InvoiceItemUpdate> items;

    @JsonIgnore
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    // Spring Boot's ObjectMapper ignores unknown JSON properties by default; this catches them instead.
    @JsonAnySetter
    public void addUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    @JsonIgnore
    public Map<String, Object> getUnknownFields() { return unknownFields; }

    public Integer getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }

    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }

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

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getShippingAmount() { return shippingAmount; }
    public void setShippingAmount(BigDecimal shippingAmount) { this.shippingAmount = shippingAmount; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public List<InvoiceItemUpdate> getItems() { return items; }
    public void setItems(List<InvoiceItemUpdate> items) { this.items = items; }
}
