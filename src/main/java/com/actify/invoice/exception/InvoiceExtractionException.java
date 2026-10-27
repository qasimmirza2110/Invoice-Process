package com.actify.invoice.exception;

import com.actify.invoice.dto.TokenUsage;

/**
 * Gemini could not be reached, refused the request, or sent back something we cannot parse.
 * Maps to HTTP 502 because the failure is in the external provider, not in the client's request.
 *
 * tokenUsage is only set when Gemini did answer (so tokens were consumed) but the answer
 * could not be used. For network/HTTP errors it stays null.
 *
 * kind / httpStatus / providerCode describe the failure for the retry decision (see FailureClassifier).
 * providerCode is a short provider status such as "RESOURCE_EXHAUSTED" or a quota id - never message text
 * and never invoice content.
 */
public class InvoiceExtractionException extends RuntimeException {

    /** What went wrong, as far as the retry decision is concerned. */
    public enum Kind {
        /** No usable configuration (e.g. no API key). Retrying cannot help. */
        CONFIGURATION,
        /** The provider answered with an HTTP error status (see httpStatus / providerCode). */
        HTTP_ERROR,
        /** Timeout, DNS problem, connection refused... */
        NETWORK,
        /** The provider refused to answer this content (safety block). */
        BLOCKED,
        /** The provider answered 200 but the answer cannot be used. */
        INVALID_RESPONSE,
        /** Not specified (older code paths and tests). Treated as permanent. */
        UNSPECIFIED
    }

    private final TokenUsage tokenUsage;
    private final Kind kind;
    private final Integer httpStatus;
    private final String providerCode;
    private final Long retryAfterSeconds;

    public InvoiceExtractionException(String message) {
        this(message, null, null);
    }

    public InvoiceExtractionException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public InvoiceExtractionException(String message, TokenUsage tokenUsage, Throwable cause) {
        this(message, Kind.UNSPECIFIED, null, null, tokenUsage, cause);
    }

    public InvoiceExtractionException(String message, Kind kind, Integer httpStatus, String providerCode,
                                      TokenUsage tokenUsage, Throwable cause) {
        this(message, kind, httpStatus, providerCode, tokenUsage, null, cause);
    }

    /** retryAfterSeconds: how long the provider asked us to wait (Retry-After / RetryInfo), or null. */
    public InvoiceExtractionException(String message, Kind kind, Integer httpStatus, String providerCode,
                                      TokenUsage tokenUsage, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
        this.kind = kind == null ? Kind.UNSPECIFIED : kind;
        this.httpStatus = httpStatus;
        this.providerCode = providerCode;
        this.tokenUsage = tokenUsage;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
