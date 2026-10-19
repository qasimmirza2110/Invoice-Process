package com.actify.invoice.quota;

import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceExtractionException.Kind;
import com.actify.invoice.quota.FailureClassifier.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void transientHttpErrorsAndNetworkProblemsAreRetried() {
        for (int status : new int[]{408, 429, 500, 502, 503, 504}) {
            assertThat(FailureClassifier.classify(http(status, null))).as("HTTP " + status).isEqualTo(Decision.RETRY);
        }
        assertThat(FailureClassifier.classify(failure(Kind.NETWORK))).isEqualTo(Decision.RETRY);
    }

    @Test
    void clientErrorsAndUnusableAnswersAreNeverRetried() {
        for (int status : new int[]{400, 401, 402, 403, 404, 409, 413}) {
            assertThat(FailureClassifier.classify(http(status, null))).as("HTTP " + status).isEqualTo(Decision.FAIL);
        }
        for (Kind kind : new Kind[]{Kind.CONFIGURATION, Kind.BLOCKED, Kind.INVALID_RESPONSE, Kind.UNSPECIFIED}) {
            assertThat(FailureClassifier.classify(failure(kind))).as(kind.name()).isEqualTo(Decision.FAIL);
        }
    }

    @Test
    void a429WithADailyQuotaIdWaitsForTheDailyReset() {
        assertThat(FailureClassifier.classify(http(429, "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel-FreeTier")))
                .isEqualTo(Decision.WAIT_FOR_DAILY_RESET);
        assertThat(FailureClassifier.classify(http(429, "quota_exceeded"))).isEqualTo(Decision.WAIT_FOR_DAILY_RESET);
        // A per-minute quota id is an ordinary retry.
        assertThat(FailureClassifier.classify(http(429, "RESOURCE_EXHAUSTED GenerateRequestsPerMinutePerProjectPerModel")))
                .isEqualTo(Decision.RETRY);
    }

    @Test
    void errorCodesAreShortAndSafe() {
        assertThat(FailureClassifier.errorCode(http(503, "UNAVAILABLE"))).isEqualTo("HTTP_503");
        assertThat(FailureClassifier.errorCode(failure(Kind.NETWORK))).isEqualTo("NETWORK");
    }

    private static InvoiceExtractionException http(int status, String providerCode) {
        return new InvoiceExtractionException("x", Kind.HTTP_ERROR, status, providerCode, null, null);
    }

    private static InvoiceExtractionException failure(Kind kind) {
        return new InvoiceExtractionException("x", kind, null, null, null, null);
    }
}
