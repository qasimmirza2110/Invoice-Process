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
 *
 * FREE_TIER_TEST mode (invoice.gemini.free-tier-test-confirmed, INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED): the owner
 * states that the key is a FREE-tier key and that only ONE selected test invoice will be sent - one authorized for this
 * use and non-confidential (free-tier content may be used by Google), never a confidential one. It does
 * not claim a paid project and does not switch the paid-tier requirement off. SchemaSafetyGuard.checkGeminiModes makes
 * it fail closed at startup (exactly one allowlisted invoice, official endpoint, fixed model, limits within the
 * operator-reported free-tier ceilings, no legacy endpoint); the worker then makes at most one Gemini call per run and
 * never retries (ExtractionWorker, ExtractionJobService). Both confirmations together are a conflict: nothing is sent.
 */
@Configuration
public class GeminiConfig {

    public static final String PAID_TIER_BLOCKED_REASON = "Extraction is blocked: real invoices may only be sent to a "
            + "paid Gemini API project. After checking that the API key's Google Cloud project has active billing, "
            + "set INVOICE_GEMINI_PAID_TIER_CONFIRMED=true. (A controlled test with ONE selected test invoice and a free-tier "
            + "key uses INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED instead - see docs/RENDER_DEPLOYMENT.md.)";

    public static final String CONFLICT_BLOCKED_REASON = "Extraction is blocked: INVOICE_GEMINI_PAID_TIER_CONFIRMED and "
            + "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED are both true. Set only the one that matches the key's project.";

    /** What the data-policy gate currently allows (shown on the admin queue status; never secret). */
    public enum Mode {
        /** A paid project is required and nothing is confirmed: nothing is sent. */
        NOT_CONFIRMED,
        /** The owner confirmed a paid (billing-enabled) project. */
        PAID,
        /** The owner confirmed a free-tier key for ONE selected (allowlisted) test invoice. */
        FREE_TIER_TEST,
        /** The local demo: no paid-tier requirement (synthetic files on a developer machine). */
        LOCAL_DEMO,
        /** Both confirmations at once: refused at startup, and nothing is sent. */
        CONFLICT
    }

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

    @Value("${invoice.gemini.free-tier-test-confirmed:false}")
    private boolean freeTierTestConfirmed;

    public GeminiConfig() {
    }

    // Used by unit tests so they don't need a Spring context. No data-policy requirement (synthetic test data).
    public GeminiConfig(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, false, false);
    }

    public GeminiConfig(String apiKey, String model, String baseUrl, boolean requirePaidTier, boolean paidTierConfirmed) {
        this(apiKey, model, baseUrl, requirePaidTier, paidTierConfirmed, false);
    }

    public GeminiConfig(String apiKey, String model, String baseUrl, boolean requirePaidTier, boolean paidTierConfirmed,
                        boolean freeTierTestConfirmed) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.requirePaidTier = requirePaidTier;
        this.paidTierConfirmed = paidTierConfirmed;
        this.freeTierTestConfirmed = freeTierTestConfirmed;
    }

    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }

    public Mode getMode() {
        if (paidTierConfirmed && freeTierTestConfirmed) {
            return Mode.CONFLICT;
        }
        if (freeTierTestConfirmed) {
            return Mode.FREE_TIER_TEST;
        }
        if (paidTierConfirmed) {
            return Mode.PAID;
        }
        return requirePaidTier ? Mode.NOT_CONFIRMED : Mode.LOCAL_DEMO;
    }

    /** FREE_TIER_TEST mode: one allowlisted test invoice, at most one Gemini call per run, no retries. */
    public boolean isFreeTierTest() {
        return getMode() == Mode.FREE_TIER_TEST;
    }

    /**
     * False while a paid project is required but neither a paid project nor a free-tier test is confirmed, and always
     * when both are confirmed (a conflict): then no document may be sent to Gemini.
     */
    public boolean isDocumentSendingAllowed() {
        return getBlockedReason() == null;
    }

    /** Why nothing may be sent (for logs and the admin status), or null when sending is allowed. */
    public String getBlockedReason() {
        switch (getMode()) {
            case CONFLICT:
                return CONFLICT_BLOCKED_REASON;
            case NOT_CONFIRMED:
                return PAID_TIER_BLOCKED_REASON;
            default:
                return null;
        }
    }
}
