package com.actify.invoice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini settings from application.properties (which reads them from environment variables).
 * Deliberately no toString(): this object holds the API key and must never end up in a log.
 *
 * Data policy for real invoices (checked 2026-10-28 against https://ai.google.dev/gemini-api/terms, last updated
 * 2026-04-28): content sent to UNPAID Gemini API services may be used to improve Google's products and may be read by
 * human reviewers, and the terms say not to submit confidential or personal information to them. The API counts as
 * a Paid Service only through a Google Cloud project with an active billing account. This application cannot see
 * the billing status, so the owner confirms it: with invoice.gemini.require-paid-tier=true (the default) nothing is
 * sent to Gemini until invoice.gemini.paid-tier-confirmed=true (INVOICE_GEMINI_PAID_TIER_CONFIRMED). Only the local
 * demo (synthetic files on a developer machine) switches the requirement off.
 */
@Configuration
public class GeminiConfig {

    public static final String PAID_TIER_BLOCKED_REASON = "Extraction is blocked: real invoices may only be sent to a "
            + "paid Gemini API project. After checking that the API key's Google Cloud project has active billing, "
            + "set INVOICE_GEMINI_PAID_TIER_CONFIRMED=true.";

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${invoice.gemini.require-paid-tier:true}")
    private boolean requirePaidTier = true;

    @Value("${invoice.gemini.paid-tier-confirmed:false}")
    private boolean paidTierConfirmed;

    public GeminiConfig() {
    }

    // Used by unit tests so they don't need a Spring context. No data-policy requirement (synthetic test data).
    public GeminiConfig(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, false, false);
    }

    public GeminiConfig(String apiKey, String model, String baseUrl, boolean requirePaidTier, boolean paidTierConfirmed) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.requirePaidTier = requirePaidTier;
        this.paidTierConfirmed = paidTierConfirmed;
    }

    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }

    /** False while a paid project is required but not confirmed: then no document may be sent to Gemini. */
    public boolean isDocumentSendingAllowed() {
        return !requirePaidTier || paidTierConfirmed;
    }
}
