package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.GeminiCallAttempt.Outcome;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceExtractionException.Kind;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.queue.ExtractionJobService.ClaimedJob;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.quota.QuotaStatus;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The worker's decisions, with every collaborator mocked: no database, no Gemini, no PDF on disk.
 * Proves a missing key never touches the queue, and each provider outcome leads to the right invoice state.
 */
class ExtractionWorkerDecisionTest {

    private static final byte[] PDF = "%PDF-1.4 synthetic".getBytes();

    private final ExtractionJobService jobs = mock(ExtractionJobService.class);
    private final ExtractionOutcomeService outcomes = mock(ExtractionOutcomeService.class);
    private final InvoiceDocumentStorage storage = mock(InvoiceDocumentStorage.class);
    private final LlmInvoiceExtractor extractor = mock(LlmInvoiceExtractor.class);
    private final GeminiQuotaGuard quotaGuard = mock(GeminiQuotaGuard.class);
    private final QuotaSettings limits = new QuotaSettings("10", "100000", "100", "3000", 0.8);

    private ExtractionWorker worker(boolean enabled, String apiKey) {
        return new ExtractionWorker(jobs, outcomes, storage, extractor, quotaGuard, limits,
                new GeminiConfig(apiKey, "test-model", "http://127.0.0.1:9"), enabled);
    }

    private static ClaimedJob job(int attempt, boolean attemptsLeft) {
        return new ClaimedJob(17L, "doc-uuid", "sha", attempt, attemptsLeft, 500L);
    }

    @Test
    void anEnabledWorkerWithoutAnApiKeyNeverClaimsAJob() {
        ExtractionWorker w = worker(true, "");
        assertThat(w.isRunning()).isFalse();
        w.poll();
        verifyNoInteractions(jobs, outcomes, extractor, quotaGuard);
    }

    @Test
    void aDisabledWorkerNeverClaimsAJobEvenWithAKey() {
        ExtractionWorker w = worker(false, "k");
        assertThat(w.isRunning()).isFalse();
        w.poll();
        verifyNoInteractions(jobs, outcomes, extractor, quotaGuard);
    }

    @Test
    void withKeyAndLimitsTheWorkerRuns() {
        assertThat(worker(true, "k").isRunning()).isTrue();
        assertThat(worker(true, "k").hasApiKey()).isTrue();
    }

    @Test
    void successStoresTheResultForReviewWithTheReportedUsage() {
        when(jobs.claimNext(anyString())).thenReturn(job(1, true));
        when(storage.read("doc-uuid", "sha")).thenReturn(PDF);
        TokenUsage usage = new TokenUsage();
        usage.setTotalTokenCount(1234L);
        LlmExtractionResponse response = new LlmExtractionResponse(new InvoiceExtractionResult(), usage);
        when(extractor.extract(PDF)).thenReturn(response);

        assertThat(worker(true, "k").processNextJob()).isTrue();

        verify(quotaGuard).complete(eq(500L), eq(Outcome.SUCCESS), eq(200), isNull(), eq(usage), anyLong());
        verify(outcomes).storeForReview(eq(17L), eq(response), anyLong());
    }

    @Test
    void aServerErrorWithAttemptsLeftIsRetriedLater() {
        failWith(new InvoiceExtractionException("Gemini request failed with HTTP 503", Kind.HTTP_ERROR, 503, null, null, null), true);
        verify(outcomes).scheduleRetry(eq(17L), eq("HTTP_503"), anyString(), any(LocalDateTime.class), isNull(), anyLong());
        verify(outcomes, never()).markFailed(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aPerMinute429WaitsAtLeastAsLongAsGoogleAsked() {
        LocalDateTime before = LocalDateTime.now();
        failWith(new InvoiceExtractionException("429", Kind.HTTP_ERROR, 429, "RESOURCE_EXHAUSTED", null, 600L, null), true);
        org.mockito.ArgumentCaptor<LocalDateTime> next = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outcomes).scheduleRetry(eq(17L), eq("HTTP_429"), anyString(), next.capture(), isNull(), anyLong());
        // our first back-off would be ~30 s; the provider asked for 600 s, so that wins
        assertThat(next.getValue()).isAfterOrEqualTo(before.plusSeconds(600));
    }

    @Test
    void theProviderHintNeverShortensOurBackoffAndIsCapped() {
        assertThat(ExtractionWorker.retryDelaySeconds(1, 0.5, null)).isEqualTo(30);
        assertThat(ExtractionWorker.retryDelaySeconds(1, 0.5, 5L)).isEqualTo(30);        // shorter hint: ours stays
        assertThat(ExtractionWorker.retryDelaySeconds(1, 0.5, 37L)).isEqualTo(37);       // longer hint wins
        assertThat(ExtractionWorker.retryDelaySeconds(1, 0.5, 86_400L)).isEqualTo(3600); // capped at 1 h
        assertThat(ExtractionWorker.retryDelaySeconds(2, 0.5, 0L)).isEqualTo(120);
    }

    @Test
    void aDailyQuota429WaitsForTheResetInsteadOfFailing() {
        when(quotaGuard.nextDailyReset()).thenReturn(LocalDateTime.of(2026, 10, 24, 12, 30));
        failWith(new InvoiceExtractionException("429", Kind.HTTP_ERROR, 429, "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel",
                null, null), true);
        verify(outcomes).waitForDailyReset(eq(17L), eq("HTTP_429"), eq(LocalDateTime.of(2026, 10, 24, 12, 31)), isNull(), anyLong());
        verify(outcomes, never()).markFailed(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aMalformedAnswerFailsAtOnceWithItsSafeReasonAndKeepsTheReportedTokens() {
        TokenUsage usage = new TokenUsage();
        usage.setTotalTokenCount(900L);
        failWith(new InvoiceExtractionException("Gemini answer does not match the expected invoice JSON format",
                Kind.INVALID_RESPONSE, null, null, usage, null), true);
        verify(outcomes).markFailed(eq(17L), eq("INVALID_RESPONSE"), contains("expected invoice JSON"), eq(usage), anyLong());
        verify(outcomes, never()).scheduleRetry(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void aTimeoutOnTheLastAttemptGivesUpInsteadOfRetryingForever() {
        failWith(new InvoiceExtractionException("Could not reach Gemini (network error or timeout)", Kind.NETWORK, null, null, null, null),
                false);
        verify(outcomes).markFailed(eq(17L), eq("NETWORK"), contains("gave up after 3 attempts"), isNull(), anyLong());
        verify(outcomes, never()).scheduleRetry(any(), any(), any(), any(), any(), anyLong());
    }

    private void failWith(InvoiceExtractionException e, boolean attemptsLeft) {
        when(jobs.claimNext(anyString())).thenReturn(job(attemptsLeft ? 1 : 3, attemptsLeft));
        when(storage.read("doc-uuid", "sha")).thenReturn(PDF);
        when(extractor.extract(PDF)).thenThrow(e);
        assertThat(worker(true, "k").processNextJob()).isTrue();
    }

    // ---- the admin's "why is nothing moving?" answer ----

    private WorkerStatusService status(boolean enabled, String key) {
        return new WorkerStatusService(worker(enabled, key), jobs, quotaGuard);
    }

    private static QuotaStatus quota(boolean configured) {
        QuotaStatus q = mock(QuotaStatus.class);
        when(q.isConfigured()).thenReturn(configured);
        when(q.getConfigurationProblem()).thenReturn("gemini.limits.rpm (GEMINI_LIMIT_RPM) is not set.");
        return q;
    }

    @Test
    void theIdleReasonSeparatesWorkerOffMissingKeyAndMissingLimits() {
        assertThat(status(false, "").idleReason(quota(true)))
                .contains("switched off").contains("no Gemini API key").contains("not set up in this run.").doesNotContain("local-demo");
        assertThat(status(false, "k").idleReason(quota(true))).isEqualTo("The extraction worker is switched off (INVOICE_WORKER_ENABLED is not true).");
        assertThat(status(true, "").idleReason(quota(true))).contains("GEMINI_API_KEY is not set");
        assertThat(status(true, "").isWorkerRunning(quota(true))).isFalse();
        assertThat(status(true, "k").idleReason(quota(false))).startsWith("Gemini limits are not configured");
        assertThat(status(true, "k").isWorkerRunning(quota(true))).isTrue();
        // The key value itself is never part of any answer.
        assertThat(status(true, "secret-key-value").idleReason(quota(false))).doesNotContain("secret-key-value");
    }

    // ---- invoice.worker.only-invoice-ids ----

    @Test
    void theInvoiceAllowListIsParsedStrictly() {
        assertThat(ExtractionJobService.parseIds("")).isEmpty();
        assertThat(ExtractionJobService.parseIds(null)).isEmpty();
        assertThat(ExtractionJobService.parseIds(" 17 , 18")).containsExactly(17L, 18L);
        assertThatThrownBy(() -> ExtractionJobService.parseIds("17,abc")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVOICE_WORKER_ONLY_INVOICE_IDS");
    }
}
