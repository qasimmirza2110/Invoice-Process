package com.actify.invoice.dto;

/**
 * What one LLM call gives back: the extracted invoice data plus the token usage of that call.
 * Kept as two separate objects so provider metadata never gets mixed into the invoice schema.
 */
public class LlmExtractionResponse {

    private final InvoiceExtractionResult invoice;
    private final TokenUsage tokenUsage;     // may be null if the provider gave no metadata

    public LlmExtractionResponse(InvoiceExtractionResult invoice, TokenUsage tokenUsage) {
        this.invoice = invoice;
        this.tokenUsage = tokenUsage;
    }

    public InvoiceExtractionResult getInvoice() { return invoice; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
}
