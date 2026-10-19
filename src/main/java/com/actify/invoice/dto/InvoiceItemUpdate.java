package com.actify.invoice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line item in a correction request. Only these five fields are accepted; anything else
 * (e.g. "id", "invoiceId") is collected in unknownFields and the request is rejected with 400.
 */
public class InvoiceItemUpdate {

    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private BigDecimal lineTotal;

    @JsonIgnore
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public InvoiceItemUpdate() {
    }

    public InvoiceItemUpdate(String description, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal taxRate, BigDecimal lineTotal) {
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRate = taxRate;
        this.lineTotal = lineTotal;
    }

    // Spring Boot's ObjectMapper ignores unknown JSON properties by default; this catches them instead.
    @JsonAnySetter
    public void addUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    @JsonIgnore
    public Map<String, Object> getUnknownFields() { return unknownFields; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}
