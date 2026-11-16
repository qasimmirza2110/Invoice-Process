package com.actify.invoice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini settings from application.properties (which reads them from environment variables).
 * Deliberately no toString(): this object holds the API key and must never end up in a log.
 *
 * ONE provider, ONE mode: Google Gemini (gemini-3.5-flash-lite) through a FREE-tier key, via the official endpoint
 * only. There is no paid mode, no paid fallback, no second provider and no invoice-id allowlist.
 *
 * Data policy (https://ai.google.dev/gemini-api/terms): content sent to UNPAID Gemini API services may be used to
 * improve Google's products and may be read by human reviewers; do not submit sensitive, confidential or personal
 * information. Whether a key is billed is decided by its Google Cloud project, not by this application or the URL, so
 * the operator confirms it with invoice.gemini.free-tier-auto-queue-confirmed (INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED):
 * "this key's project has no active Cloud Billing account, and I accept the free-tier data terms for uploads whose
 * uploader gave free-tier consent". Without it nothing is sent and queued invoices simply wait.
 *
 * Eligibility is per record: the worker takes, oldest first, only QUEUED invoices of a batch created with
 * freeTierProcessingConsent=true (upload_batches.free_tier_consent). Every other invoice stays QUEUED and is never sent.
 * The consent is the uploader's attestation; the application cannot read a PDF and judge whether it is confidential.
 */
@Configuration
public class GeminiConfig {

    public static final String NOT_CONFIRMED_REASON = "Extraction is blocked: INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED "
            + "is not true. Set it only after checking that the API key's Google Cloud project has NO active billing "
            + "account and accepting the Gemini free-tier data terms (Google may use submitted content and humans may "
            + "read it). Even then only invoices uploaded with freeTierProcessingConsent=true are sent "
            + "(docs/RENDER_DEPLOYMENT.md, section 4).";

    /** What the data-policy gate currently allows (shown on the admin queue status; never secret). */
    public enum Mode {
        /** The operator has not confirmed the free-tier queue: nothing is sent. */
        NOT_CONFIRMED,
        /** Free-tier key confirmed: FIFO queue of invoices uploaded WITH free-tier consent only. */
        FREE_TIER_AUTO_QUEUE
    }

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${invoice.gemini.free-tier-auto-queue-confirmed:false}")
    private boolean freeTierAutoQueueConfirmed;

    public GeminiConfig() {
    }

    /** Used by unit tests that only exercise the HTTP client (mode NOT_CONFIRMED). */
    public GeminiConfig(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, false);
    }

    public GeminiConfig(String apiKey, String model, String baseUrl, boolean freeTierAutoQueueConfirmed) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.freeTierAutoQueueConfirmed = freeTierAutoQueueConfirmed;
    }

    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }

    public Mode getMode() {
        return freeTierAutoQueueConfirmed ? Mode.FREE_TIER_AUTO_QUEUE : Mode.NOT_CONFIRMED;
    }

    /** True when the operator confirmed the free-tier queue (consented uploads only, FIFO, never a paid fallback). */
    public boolean isFreeTierAutoQueue() {
        return getMode() == Mode.FREE_TIER_AUTO_QUEUE;
    }

    /** False until the free-tier queue is confirmed: then no document may be sent to Gemini. */
    public boolean isDocumentSendingAllowed() {
        return getBlockedReason() == null;
    }

    /** Why nothing may be sent (for logs and the admin status), or null when sending is allowed. */
    public String getBlockedReason() {
        return isFreeTierAutoQueue() ? null : NOT_CONFIRMED_REASON;
    }
}
