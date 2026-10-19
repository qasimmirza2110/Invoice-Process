package com.actify.invoice.llm;

import com.actify.invoice.dto.LlmExtractionResponse;

/**
 * The service layer only knows "give me invoice data for this PDF".
 * Which LLM provider answers (Gemini today) is an implementation detail behind this interface,
 * and it also lets the service tests use a mock instead of calling a real API.
 */
public interface LlmInvoiceExtractor {

    /**
     * Sends the whole PDF (all pages) in ONE request.
     *
     * @return the extracted invoice plus the token usage reported in the same response
     * @throws com.actify.invoice.exception.InvoiceExtractionException if the provider fails
     *         or returns something that cannot be parsed
     */
    LlmExtractionResponse extract(byte[] pdfBytes);
}
