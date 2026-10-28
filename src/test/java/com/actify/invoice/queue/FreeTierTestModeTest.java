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
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.workflow.WorkflowService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FREE_TIER_TEST mode with every collaborator mocked - no database, no Gemini, no PDF on disk.
 * Proves: one Gemini call per run, no automatic retry of any kind, a safe halt with a useful non-secret reason, and
 * that the paid-mode retry behaviour is unchanged. The PostgreSQL side (allowlist SQL) is FreeTierQueuePgIntegrationTest.
 */
class FreeTierTestModeTest {

    private static final byte[] PDF = "%PDF-1.4 synthetic".getBytes();
    private static final String KEY = "free-tier-key-value-that-must-never-appear";

    private final ExtractionJobService jobs = mock(ExtractionJobService.class);
    private final ExtractionOutcomeService outcomes = mock(ExtractionOutcomeService.class);
    private final InvoiceDocumentStorage storage = mock(InvoiceDocumentStorage.class);
    private final LlmInvoiceExtractor extractor = mock(LlmInvoiceExtractor.class);
    private final GeminiQuotaGuard quotaGuard = mock(GeminiQuotaGuard.class);
    private final QuotaSettings limits = new QuotaSettings("5", "50000", "20", "5000", 0.8);

    private static GeminiConfig freeTier() {
        return new GeminiConfig(KEY, "gemini-3.5-flash-lite", "http://127.0.0.1:9", true, false, true);
    }

    private static GeminiConfig paid() {
        return new GeminiConfig(KEY, "gemini-3.5-flash-lite", "http://127.0.0.1:9", true, true, false);
    }

    private ExtractionWorker worker(GeminiConfig config) {
        return new ExtractionWorker(jobs, outcomes, storage, extractor, quotaGuard, limits, config, true);
    }

    /** First claim returns the test invoice (with attempts left, as a fresh job has); any later claim nothing. */
    private void oneQueuedTestInvoice() {
        when(jobs.claimNext(anyString())).thenReturn(new ClaimedJob(17L, "doc-uuid", "sha", 1, true, 500L), (ClaimedJob) null);
        when(jobs.getOnlyInvoiceIds()).thenReturn(Collections.singleton(17L));
        when(storage.read("doc-uuid", "sha")).thenReturn(PDF);
    }

    private ExtractionWorker runOnceFailingWith(InvoiceExtractionException e) {
        oneQueuedTestInvoice();
        when(extractor.extract(PDF)).thenThrow(e);
        ExtractionWorker w = worker(freeTier());
        w.poll();
        return w;
    }

    /** After the one call: halted, no second claim on later polls, and the admin sees why. */
    private void assertHaltedAfterOneCall(ExtractionWorker w, String resultCode) {
        assertThat(w.isRunning()).isFalse();
        assertThat(w.processNextJob()).isFalse();
        w.poll();
        w.poll();
        verify(jobs, times(1)).claimNext(anyString());
        verify(extractor, times(1)).extract(any(byte[].class));
        assertThat(w.getHaltReason()).contains("invoice 17").contains(resultCode).doesNotContain(KEY);

        QuotaStatus quota = mock(QuotaStatus.class);
        when(quota.isConfigured()).thenReturn(true);
        WorkerStatusService status = new WorkerStatusService(w, jobs, quotaGuard);
        assertThat(status.isWorkerRunning(quota)).isFalse();
        assertThat(status.idleReason(quota)).isEqualTo(w.getHaltReason());
        assertThat(status.geminiMode()).isEqualTo(GeminiConfig.Mode.FREE_TIER_TEST);
    }

    private void assertFinalFailureWithoutRetry(String code, Outcome outcome) {
        verify(quotaGuard).complete(eq(500L), eq(outcome), any(), eq(code), any(), anyLong());
        verify(outcomes).markFailed(eq(17L), eq(code), contains("free-tier test: no automatic retry"), any(), anyLong());
        verify(outcomes, never()).scheduleRetry(any(), any(), any(), any(), any(), anyLong());
        verify(outcomes, never()).waitForDailyReset(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aSuccessfulCallGoesToReviewAndThenTheWorkerHalts() {
        oneQueuedTestInvoice();
        LlmExtractionResponse response = new LlmExtractionResponse(new InvoiceExtractionResult(), new TokenUsage());
        when(extractor.extract(PDF)).thenReturn(response);
        ExtractionWorker w = worker(freeTier());

        w.poll();

        verify(outcomes).storeForReview(eq(17L), eq(response), anyLong());
        verify(quotaGuard).recoverInFlight(Collections.singleton(17L));
        assertHaltedAfterOneCall(w, "SUCCESS");
    }

    @Test
    void aTransientServerErrorIsNotRetriedEvenWithAttemptsLeft() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException("Gemini request failed with HTTP 503",
                Kind.HTTP_ERROR, 503, null, null, null));
        assertFinalFailureWithoutRetry("HTTP_503", Outcome.RETRYABLE_ERROR);
        assertHaltedAfterOneCall(w, "HTTP_503");
    }

    @Test
    void aPerMinuteQuota429IsNotRetried() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException("429", Kind.HTTP_ERROR, 429,
                "RESOURCE_EXHAUSTED", null, 30L, null));
        assertFinalFailureWithoutRetry("HTTP_429", Outcome.RETRYABLE_ERROR);
        assertHaltedAfterOneCall(w, "HTTP_429");
    }

    @Test
    void aDailyQuota429FailsInsteadOfWaitingForTheResetButStillPausesTheQuota() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException("429", Kind.HTTP_ERROR, 429,
                "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel", null, null));
        // Recorded as DAILY_QUOTA, so GeminiQuotaGuard pauses every caller until the Pacific-time reset.
        assertFinalFailureWithoutRetry("HTTP_429", Outcome.DAILY_QUOTA);
        assertHaltedAfterOneCall(w, "HTTP_429");
    }

    @Test
    void anInvalidKeyFailsOnceAndHaltsWithoutShowingTheKey() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException(
                "Gemini rejected the API key or the project has no access (HTTP 400)", Kind.HTTP_ERROR, 400, null, null, null));
        assertFinalFailureWithoutRetry("HTTP_400", Outcome.PERMANENT_ERROR);
        assertHaltedAfterOneCall(w, "HTTP_400");
        verify(outcomes, never()).markFailed(any(), any(), contains(KEY), any(), anyLong());
    }

    @Test
    void aForbiddenProjectFailsOnceAndHalts() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException(
                "Gemini rejected the API key or the project has no access (HTTP 403)", Kind.HTTP_ERROR, 403, null, null, null));
        assertFinalFailureWithoutRetry("HTTP_403", Outcome.PERMANENT_ERROR);
        assertHaltedAfterOneCall(w, "HTTP_403");
    }

    @Test
    void aConfigurationErrorFailsOnceAndHalts() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException("Gemini API key is not configured.",
                Kind.CONFIGURATION, null, null, null, null));
        assertFinalFailureWithoutRetry("CONFIGURATION", Outcome.PERMANENT_ERROR);
        assertHaltedAfterOneCall(w, "CONFIGURATION");
    }

    @Test
    void aNetworkErrorIsNotRetried() {
        ExtractionWorker w = runOnceFailingWith(new InvoiceExtractionException("Could not reach Gemini (network error or timeout)",
                Kind.NETWORK, null, null, null, null));
        assertFinalFailureWithoutRetry("NETWORK", Outcome.RETRYABLE_ERROR);
        assertHaltedAfterOneCall(w, "NETWORK");
    }

    @Test
    void theWorkerHaltsEvenWhenRecordingTheOutcomeItselfFails() {
        oneQueuedTestInvoice();
        when(extractor.extract(PDF)).thenThrow(new IllegalStateException("boom"));
        doThrow(new IllegalStateException("db down")).when(outcomes).markFailed(any(), any(), any(), any(), anyLong());
        ExtractionWorker w = worker(freeTier());

        w.poll(); // poll() logs the exception instead of killing the scheduler thread

        assertHaltedAfterOneCall(w, "UNEXPECTED_ERROR");
    }

    @Test
    void paidModeRetryBehaviourIsUnchanged() {
        when(jobs.claimNext(anyString())).thenReturn(new ClaimedJob(17L, "doc-uuid", "sha", 1, true, 500L));
        when(storage.read("doc-uuid", "sha")).thenReturn(PDF);
        when(extractor.extract(PDF)).thenThrow(new InvoiceExtractionException("Gemini request failed with HTTP 503",
                Kind.HTTP_ERROR, 503, null, null, null));
        ExtractionWorker w = worker(paid());

        assertThat(w.processNextJob()).isTrue();

        verify(outcomes).scheduleRetry(eq(17L), eq("HTTP_503"), anyString(), any(LocalDateTime.class), isNull(), anyLong());
        verify(outcomes, never()).markFailed(any(), any(), any(), any(), anyLong());
        assertThat(w.getHaltReason()).isNull();
        assertThat(w.isRunning()).isTrue();
    }

    @Test
    void aWorkerThatIsNotEnabledNeverClaimsInFreeTierModeEither() {
        ExtractionWorker w = new ExtractionWorker(jobs, outcomes, storage, extractor, quotaGuard, limits, freeTier(), false);
        assertThat(w.isRunning()).isFalse();
        w.poll();
        verifyNoInteractions(jobs, outcomes, extractor, quotaGuard);
    }

    // ---- ExtractionJobService: the second line of defence behind SchemaSafetyGuard ----

    private final ExtractionJobRepository jobRepository = mock(ExtractionJobRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final WorkflowService workflow = mock(WorkflowService.class);

    private ExtractionJobService jobService(GeminiConfig config, String ids) {
        return new ExtractionJobService(jobRepository, invoiceRepository, workflow, quotaGuard, config, 10, ids);
    }

    @Test
    void withoutExactlyOneAllowlistedInvoiceNothingIsClaimedOrRecovered() {
        for (String ids : new String[]{"", "17, 18"}) {
            ExtractionJobService service = jobService(freeTier(), ids);
            assertThat(service.claimNext("w")).as(ids).isNull();
            assertThat(service.getLastQuotaReason()).as(ids).isEqualTo(ExtractionJobService.FREE_TIER_NEEDS_ONE_INVOICE);
            assertThat(service.recoverStaleClaims()).as(ids).isZero();
        }
        verifyNoInteractions(jobRepository, invoiceRepository, workflow, quotaGuard);
    }

    @Test
    void anInvoiceThatWasAlreadySentOnceIsNeverClaimedAgain() {
        when(quotaGuard.check(true)).thenReturn(GeminiQuotaGuard.Check.allowed(5000));
        when(jobRepository.lockNextEligibleAmong(any(LocalDateTime.class), anyCollection()))
                .thenReturn(Collections.singletonList(17L));
        when(quotaGuard.hasAnyAttempt(17L)).thenReturn(true);
        ExtractionJobService service = jobService(freeTier(), "17");

        assertThat(service.claimNext("w")).isNull();

        assertThat(service.getLastQuotaReason()).contains("invoice 17").contains("already sent");
        verify(jobRepository, never()).findById(any());
        verify(quotaGuard, never()).recordStart(any(), any(), anyInt());
        verifyNoInteractions(workflow);
    }

    @Test
    void staleRecoveryLooksOnlyAtTheAllowlistedInvoice() {
        when(jobRepository.findStaleClaimsAmong(any(LocalDateTime.class), anyCollection())).thenReturn(Collections.emptyList());
        assertThat(jobService(freeTier(), "17").recoverStaleClaims()).isZero();
        verify(jobRepository).findStaleClaimsAmong(any(LocalDateTime.class), eq((Collection<Long>) Collections.singleton(17L)));
        verify(jobRepository, never()).findStaleClaims(any());
    }

    @Test
    void normalOperationStillRecoversEveryStaleClaim() {
        when(jobRepository.findStaleClaims(any(LocalDateTime.class))).thenReturn(Collections.emptyList());
        assertThat(jobService(paid(), "").recoverStaleClaims()).isZero();
        verify(jobRepository).findStaleClaims(any(LocalDateTime.class));
        verify(jobRepository, never()).findStaleClaimsAmong(any(), anyCollection());
    }
}
