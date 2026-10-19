package com.actify.invoice.quota;

import com.actify.invoice.exception.InvoiceExtractionException;

import java.util.Locale;

/**
 * Decides whether a failed Gemini call should be retried. Based on Google's guidance: retry only transient
 * errors (429, 408, 5xx, network) with back-off; never retry client errors (400, 401, 403, 404...).
 *
 * A 429 can mean "too many requests this minute" (wait a little) or "daily quota used up" (wait until the
 * daily reset, midnight Pacific time). The two are told apart by the provider's error details when present
 * (a quota id containing "PerDay", or the code quota_exceeded). When the details are missing, a 429 is treated as
 * per-minute; the local daily counter in GeminiQuotaGuard and repeated 429s still stop the worker safely.
 */
public final class FailureClassifier {

    public enum Decision {
        /** Try again after a back-off. */
        RETRY,
        /** Daily quota is used up: wait until the daily reset; do not count it as a failed attempt. */
        WAIT_FOR_DAILY_RESET,
        /** Retrying cannot help: mark FAILED. */
        FAIL
    }

    private FailureClassifier() {
    }

    public static Decision classify(InvoiceExtractionException e) {
        switch (e.getKind()) {
            case NETWORK:
                return Decision.RETRY;
            case HTTP_ERROR:
                return classifyHttp(e.getHttpStatus() == null ? 0 : e.getHttpStatus(), e.getProviderCode());
            default:
                // CONFIGURATION, BLOCKED, INVALID_RESPONSE, UNSPECIFIED
                return Decision.FAIL;
        }
    }

    static Decision classifyHttp(int status, String providerCode) {
        if (status == 429) {
            return isDailyQuota(providerCode) ? Decision.WAIT_FOR_DAILY_RESET : Decision.RETRY;
        }
        if (status == 408 || status == 500 || status == 502 || status == 503 || status == 504) {
            return Decision.RETRY;
        }
        return Decision.FAIL;
    }

    static boolean isDailyQuota(String providerCode) {
        if (providerCode == null) {
            return false;
        }
        String code = providerCode.toLowerCase(Locale.ROOT);
        return code.contains("perday") || code.contains("quota_exceeded");
    }

    /** A short code stored in gemini_call_attempts.error_code and extraction_jobs.last_error_code. */
    public static String errorCode(InvoiceExtractionException e) {
        if (e.getKind() == InvoiceExtractionException.Kind.HTTP_ERROR && e.getHttpStatus() != null) {
            return "HTTP_" + e.getHttpStatus();
        }
        return e.getKind().name();
    }
}
