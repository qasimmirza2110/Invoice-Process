package com.actify.invoice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What the LLM extracted from the PDF, mapped by Jackson.
 * The values are NOT trusted yet - InvoiceExtractionValidator checks them before saving.
 *
 * invoiceDate stays a String on purpose: if the model returns "06/10/2026" we want
 * a clear validation error from our own code, not a Jackson parsing exception.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvoiceExtractionResult {

    private String invoiceNumber;
    private String invoiceDate;
    private String sellerName;
    private String sellerTaxId;
    private String buyerName;
    private String buyerTaxId;
    private String currency;
    private BigDecimal subtotal;
    // Invoice-level adjustments, both as positive amounts. null = not printed on the invoice.
    private BigDecimal discountAmount;
    private BigDecimal shippingAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private List<InvoiceItemDto> items = new ArrayList<>();

    // The exact JSON text the model returned. Not part of the LLM schema - the extractor fills it in.
    @JsonIgnore
    private String rawResponse;

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

    public List<InvoiceItemDto> getItems() { return items; }

    // The model may send "items": null - treat that the same as "no items".
    public void setItems(List<InvoiceItemDto> items) {
        this.items = items != null ? items : new ArrayList<InvoiceItemDto>();
    }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
}
