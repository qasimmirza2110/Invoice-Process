package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.GeminiCallAttempt.Outcome;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.entity.UploadBatch;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceExtractionException.Kind;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.queue.ExtractionJobService.ClaimedJob;
import com.actify.invoice.queue.WorkerStatusService.WorkerState;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.quota.QuotaStatus;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The free-tier queue with every collaborator mocked - no database, no Gemini, no PDF on disk.
 * Proves: no invoice id is configured anywhere, only consent-filtered FIFO SQL is used, an unconsented invoice is never
 * claimed, a quota refusal changes nothing, the token reservation is asked for the claimed PDF's size, the worker
 * processes many invoices without halting, rate limits defer instead of failing, and the admin status names each
 * state. The PostgreSQL side is FreeTierAutoQueuePgIntegrationTest.
 */
class FreeTierAutoQueueModeTest {

    private static final byte[] PDF = "%PDF-1.4 synthetic".getBytes();
    private static final String KEY = "auto-queue-key-value-that-must-never-appear";

    private final ExtractionJobService jobs = mock(ExtractionJobService.class);
    private final ExtractionOutcomeService outcomes = mock(ExtractionOutcomeService.class);
    private final InvoiceDocumentStorage storage = mock(InvoiceDocumentStorage.class);
    private final LlmInvoiceExtractor extractor = mock(LlmInvoiceExtractor.class);
    private final GeminiQuotaGuard quotaGuard = mock(GeminiQuotaGuard.class);
    private final QuotaSettings limits = new QuotaSettings("5", "50000", "20", "1033", 0.8);

    private static GeminiConfig config(boolean confirmed) {
        return new GeminiConfig(KEY, "gemini-3.5-flash-lite", "http://127.0.0.1:9", confirmed);
    }

    private static GeminiConfig autoQueue() {
        return config(true);
    }

    private ExtractionWorker worker(GeminiConfig config) {
        return new ExtractionWorker(jobs, outcomes, storage, extractor, quotaGuard, limits, config, true);
    }

    private static QuotaStatus quota(boolean configured, LocalDateTime pausedUntil) {
        return new QuotaStatus(configured, configured ? null : "gemini.limits.rpm (GEMINI_LIMIT_RPM) is not set.",
                "gemini-3.5-flash-lite", 5, 50000, 20, 0.8, 0, 0, 0, pausedUntil, null, 1033);
    }

    private static ClaimedJob job(long invoiceId, boolean attemptsLeft) {
        return new ClaimedJob(invoiceId, "doc-" + invoiceId, "sha-" + invoiceId, 1, attemptsLeft, 500L + invoiceId);
    }

    @BeforeEach
    void consented() {
        when(jobs.hasFreeTierConsent(anyLong())).thenReturn(true);
    }

    // ---- ExtractionJobService: consent-filtered FIFO, no allowlist ----

    private ExtractionJobService realJobs(GeminiConfig config, ExtractionJobRepository repo, GeminiQuotaGuard guard) {
        return new ExtractionJobService(repo, mock(InvoiceRepository.class), mock(WorkflowService.class), guard, config, 10);
    }

    /** A locked job whose invoice has the given consent (null = no batch at all) and a PDF of the given size. */
    private static ExtractionJob lockedJob(ExtractionJobRepository repo, long invoiceId, Boolean consent, long sizeBytes) {
        when(repo.lockNextFreeTierEligible(any())).thenReturn(Collections.singletonList(invoiceId));
        ExtractionJob job = mock(ExtractionJob.class);
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(invoiceId);
        if (consent != null) {
            UploadBatch batch = mock(UploadBatch.class);
            when(batch.isFreeTierConsent()).thenReturn(consent);
            when(invoice.getBatch()).thenReturn(batch);
        }
        InvoiceDocument document = mock(InvoiceDocument.class);
        when(document.getSizeBytes()).thenReturn(sizeBytes);
        when(document.getId()).thenReturn("doc");
        when(invoice.getSourceDocument()).thenReturn(document);
        when(job.getInvoice()).thenReturn(invoice);
        when(repo.findById(invoiceId)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void theQueueUsesOnlyTheConsentFilteredFifoQueryAndNeedsNoInvoiceIds() {
        ExtractionJobRepository repo = mock(ExtractionJobRepository.class);
        GeminiQuotaGuard guard = mock(GeminiQuotaGuard.class);
        when(repo.lockNextFreeTierEligible(any())).thenReturn(Collections.<Long>emptyList());

        assertThat(realJobs(autoQueue(), repo, guard).claimNext("w")).isNull(); // nothing with consent queued

        verify(repo).lockNextFreeTierEligible(any());
        verifyNoInteractions(guard); // no quota is reserved when nothing is waiting
    }

    @Test
    void withoutTheConfirmationNothingIsEvenLocked() {
        ExtractionJobRepository repo = mock(ExtractionJobRepository.class);
        GeminiQuotaGuard guard = mock(GeminiQuotaGuard.class);
        ExtractionJobService service = realJobs(config(false), repo, guard);
        assertThat(service.claimNext("w")).isNull();
        assertThat(service.getLastQuotaReason()).isEqualTo(GeminiConfig.NOT_CONFIRMED_REASON);
        verifyNoInteractions(repo, guard);
    }

    @Test
    void anInvoiceWithoutConsentIsNeverClaimedEvenIfTheQueryReturnedIt() {
        for (Boolean consent : new Boolean[]{null, false}) {
            ExtractionJobRepository repo = mock(ExtractionJobRepository.class);
            GeminiQuotaGuard guard = mock(GeminiQuotaGuard.class);
            ExtractionJob job = lockedJob(repo, 5L, consent, 1000);
            ExtractionJobService service = realJobs(autoQueue(), repo, guard);

            assertThat(service.claimNext("w")).isNull();

            verify(job, never()).claim(any(), anyString());
            verifyNoInteractions(guard);
            assertThat(service.getLastQuotaReason()).contains("no free-tier consent");
        }
    }

    @Test
    void aQuotaRefusalChangesNothingAndTheReservationIsSizedToTheClaimedPdf() {
        ExtractionJobRepository repo = mock(ExtractionJobRepository.class);
        GeminiQuotaGuard guard = mock(GeminiQuotaGuard.class);
        ExtractionJob job = lockedJob(repo, 7L, true, 123_456L);
        LocalDateTime later = LocalDateTime.now().plusSeconds(5);
        when(guard.check(123_456L)).thenReturn(GeminiQuotaGuard.Check.refused(
                "Input-tokens-per-minute (TPM) budget used; waiting.", later, 9000));
        ExtractionJobService service = realJobs(autoQueue(), repo, guard);

        assertThat(service.claimNext("w")).isNull();

        verify(guard).check(123_456L);
        verify(job, never()).claim(any(), anyString());
        verify(guard, never()).recordStart(any(), any(), anyInt());
        assertThat(service.getLastQuotaReason()).contains("TPM");
        assertThat(service.getLastQuotaNotBefore()).isEqualTo(later);
    }

    @Test
    void anAllowedClaimRecordsTheReservationForThatPdf() {
        ExtractionJobRepository repo = mock(ExtractionJobRepository.class);
        GeminiQuotaGuard guard = mock(GeminiQuotaGuard.class);
        ExtractionJob job = lockedJob(repo, 8L, true, 50_000L);
        when(job.getAttemptCount()).thenReturn(1);
        when(guard.check(50_000L)).thenReturn(GeminiQuotaGuard.Check.allowed(4321));
        com.actify.invoice.entity.GeminiCallAttempt attempt = mock(com.actify.invoice.entity.GeminiCallAttempt.class);
        when(attempt.getId()).thenReturn(900L);
        when(guard.recordStart(any(), any(), eq(4321))).thenReturn(attempt);

        ClaimedJob claimed = realJobs(autoQueue(), repo, guard).claimNext("w");

        assertThat(claimed).isNotNull();
        assertThat(claimed.getInvoiceId()).isEqualTo(8L);
        assertThat(claimed.getGeminiAttemptId()).isEqualTo(900L);
        verify(job).claim(any(), eq("w"));
    }

    // ---- the worker ----

    @Test
    void manyQueuedInvoicesAreProcessedOneAfterAnotherWithoutHalting() {
        when(jobs.claimNext(anyString())).thenReturn(job(101, true), job(102, true), job(103, true), null);
        for (long id = 101; id <= 103; id++) {
            when(storage.read("doc-" + id, "sha-" + id)).thenReturn(PDF);
        }
        LlmExtractionResponse response = new LlmExtractionResponse(new InvoiceExtractionResult(), new TokenUsage());
        when(extractor.extract(PDF)).thenReturn(response);
        ExtractionWorker w = worker(autoQueue());

        for (int i = 0; i < 5; i++) {
            w.poll();
        }

        verify(extractor, times(3)).extract(PDF);
        verify(outcomes).storeForReview(eq(101L), eq(response), anyLong());
        verify(outcomes).storeForReview(eq(102L), eq(response), anyLong());
        verify(outcomes).storeForReview(eq(103L), eq(response), anyLong());
        verify(quotaGuard).complete(eq(601L), eq(Outcome.SUCCESS), eq(200), any(), any(), anyLong());
        assertThat(w.isRunning()).isTrue();
        verify(quotaGuard, times(5)).recoverInFlight();
        verify(jobs, times(5)).recoverStaleClaims();
    }

    @Test
    void startupRecoversInterruptedWorkAndReportsTheWaitingQueue() {
        when(jobs.countEligibleNow()).thenReturn(4L);
        worker(autoQueue()).onStartup();
        verify(quotaGuard).recoverInFlight();
        verify(jobs).recoverStaleClaims();
        verify(jobs).countEligibleNow();
        verify(jobs, never()).claimNext(anyString()); // processing itself resumes on the next scheduled poll

        ExtractionJobService offJobs = mock(ExtractionJobService.class);
        new ExtractionWorker(offJobs, outcomes, storage, extractor, quotaGuard, limits, config(false), true).onStartup();
        verifyNoInteractions(offJobs);
    }

    private void oneJobFailingWith(InvoiceExtractionException e, boolean attemptsLeft) {
        when(jobs.claimNext(anyString())).thenReturn(job(101, attemptsLeft), (ClaimedJob) null);
        when(storage.read("doc-101", "sha-101")).thenReturn(PDF);
        when(extractor.extract(PDF)).thenThrow(e);
    }

    @Test
    void aPerMinute429DefersTheInvoiceWithoutUsingAnAttemptOrFailingIt() {
        oneJobFailingWith(new InvoiceExtractionException("rate limit", Kind.HTTP_ERROR, 429, "RESOURCE_EXHAUSTED", null, 120L,
                null), false);
        ExtractionWorker w = worker(autoQueue());
        LocalDateTime before = LocalDateTime.now();

        w.poll();

        verify(quotaGuard).complete(eq(601L), eq(Outcome.RETRYABLE_ERROR), eq(429), eq("HTTP_429"), any(), anyLong());
        verify(outcomes).deferForRateLimit(eq(101L), eq("HTTP_429"),
                org.mockito.ArgumentMatchers.argThat(t -> !t.isBefore(before.plusSeconds(119))), any(), anyLong());
        verify(outcomes, never()).markFailed(any(), any(), any(), any(), anyLong());
        verify(outcomes, never()).scheduleRetry(any(), any(), any(), any(), any(), anyLong());
        verify(extractor, times(1)).extract(PDF); // one call - no tight retry loop
        assertThat(w.isRunning()).as("a rate limit never halts the queue").isTrue();
        assertThat(w.getLastExtractionError()).startsWith("HTTP_429 (invoice 101)");
    }

    @Test
    void rateLimitDelayIsAtLeastTheCoolDownAndCapsTheProviderHint() {
        assertThat(ExtractionWorker.rateLimitDelaySeconds(null)).isEqualTo(GeminiQuotaGuard.RATE_LIMIT_COOLDOWN_SECONDS);
        assertThat(ExtractionWorker.rateLimitDelaySeconds(5L)).isEqualTo(GeminiQuotaGuard.RATE_LIMIT_COOLDOWN_SECONDS);
        assertThat(ExtractionWorker.rateLimitDelaySeconds(300L)).isEqualTo(300);
        assertThat(ExtractionWorker.rateLimitDelaySeconds(999_999L)).isEqualTo(ExtractionWorker.MAX_PROVIDER_WAIT_SECONDS);
    }

    @Test
    void aDailyQuota429WaitsForTheResetAndKeepsTheInvoiceQueued() {
        oneJobFailingWith(new InvoiceExtractionException("daily", Kind.HTTP_ERROR, 429,
                "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel-FreeTier", null, null), true);
        when(quotaGuard.nextDailyReset()).thenReturn(LocalDateTime.now().plusHours(3));

        worker(autoQueue()).poll();

        verify(quotaGuard).complete(eq(601L), eq(Outcome.DAILY_QUOTA), eq(429), eq("HTTP_429"), any(), anyLong());
        verify(outcomes).waitForDailyReset(eq(101L), eq("HTTP_429"), any(), any(), anyLong());
        verify(outcomes, never()).markFailed(any(), any(), any(), any(), anyLong());
    }

    @Test
    void networkErrorsUseTheBoundedBackOffAndThenFail() {
        oneJobFailingWith(new InvoiceExtractionException("Could not reach Gemini", Kind.NETWORK, null, null, null, null),
                true);
        worker(autoQueue()).poll();
        verify(outcomes).scheduleRetry(eq(101L), eq("NETWORK"), any(), any(), any(), anyLong());
        verify(outcomes, never()).deferForRateLimit(any(), any(), any(), any(), anyLong());
    }

    @Test
    void theLastAttemptAndPermanentErrorsAreFinalAndDiagnosable() {
        oneJobFailingWith(new InvoiceExtractionException("Could not reach Gemini", Kind.NETWORK, null, null, null, null),
                false);
        worker(autoQueue()).poll();
        verify(outcomes).markFailed(eq(101L), eq("NETWORK"), org.mockito.ArgumentMatchers.contains("gave up after 1"),
                any(), anyLong());
    }

    @Test
    void aBadRequestIsNeverRetried() {
        oneJobFailingWith(new InvoiceExtractionException("Gemini request failed with HTTP 400", Kind.HTTP_ERROR, 400,
                "INVALID_ARGUMENT", null, null), true);
        worker(autoQueue()).poll();
        verify(quotaGuard).complete(eq(601L), eq(Outcome.PERMANENT_ERROR), eq(400), eq("HTTP_400"), any(), anyLong());
        verify(outcomes).markFailed(eq(101L), eq("HTTP_400"), any(), any(), anyLong());
        verify(outcomes, never()).scheduleRetry(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void anUnexpectedIterationErrorIsReportedAndTheWorkerKeepsPolling() {
        when(jobs.claimNext(anyString())).thenThrow(new IllegalStateException("db hiccup")).thenReturn(null);
        ExtractionWorker w = worker(autoQueue());
        WorkerStatusService status = new WorkerStatusService(w, jobs, quotaGuard);

        w.poll();
        assertThat(w.getLastIterationError()).startsWith("IllegalStateException at ").doesNotContain("db hiccup");
        assertThat(status.workerState(quota(true, null), 0)).isEqualTo(WorkerState.WORKER_ERROR);
        assertThat(status.idleReason(quota(true, null))).contains("IllegalStateException");

        w.poll();
        assertThat(w.getLastIterationError()).isNull();
        assertThat(status.workerState(quota(true, null), 0)).isEqualTo(WorkerState.IDLE_NO_ELIGIBLE_INVOICE);
    }

    // ---- the admin status ----

    @Test
    void theStatusNamesEachStateWithoutTheKey() {
        WorkerStatusService auto = new WorkerStatusService(worker(autoQueue()), jobs, quotaGuard);
        assertThat(auto.geminiMode()).isEqualTo(GeminiConfig.Mode.FREE_TIER_AUTO_QUEUE);
        assertThat(auto.isWorkerRunning(quota(true, null))).isTrue();
        assertThat(auto.workerState(quota(true, null), 2)).isEqualTo(WorkerState.PROCESSING_AVAILABLE);
        assertThat(auto.workerState(quota(true, null), 0)).isEqualTo(WorkerState.IDLE_NO_ELIGIBLE_INVOICE);
        assertThat(auto.workerState(quota(false, null), 2)).isEqualTo(WorkerState.LIMITS_NOT_CONFIGURED);

        LocalDateTime reset = LocalDateTime.now().plusHours(5);
        assertThat(auto.workerState(quota(true, reset), 2)).isEqualTo(WorkerState.DAILY_QUOTA_PAUSED);
        assertThat(auto.resumeAt(quota(true, reset))).isEqualTo(reset);
        assertThat(auto.idleReason(quota(true, reset))).contains("RPD").contains("resume automatically");

        QuotaStatus coolingDown = quota(true, null);
        LocalDateTime coolDownEnd = LocalDateTime.now().plusSeconds(40);
        coolingDown.setExhaustion(false, false, false, coolDownEnd, 4, 40000, 16);
        assertThat(auto.workerState(coolingDown, 2)).isEqualTo(WorkerState.WAITING_FOR_QUOTA);
        assertThat(auto.resumeAt(coolingDown)).isEqualTo(coolDownEnd);
        assertThat(auto.idleReason(coolingDown)).contains("429");

        LocalDateTime paced = LocalDateTime.now().plusSeconds(4);
        when(jobs.getLastQuotaReason()).thenReturn("Requests-per-minute (RPM) budget used; waiting.");
        when(jobs.getLastQuotaNotBefore()).thenReturn(paced);
        assertThat(auto.workerState(quota(true, null), 2)).isEqualTo(WorkerState.WAITING_FOR_QUOTA);
        assertThat(auto.resumeAt(quota(true, null))).isEqualTo(paced);

        WorkerStatusService none = new WorkerStatusService(worker(config(false)), jobs, quotaGuard);
        assertThat(none.workerState(quota(true, null), 2)).isEqualTo(WorkerState.MODE_NOT_CONFIRMED);
        assertThat(none.idleReason(quota(true, null))).doesNotContain(KEY);
        WorkerStatusService off = new WorkerStatusService(new ExtractionWorker(jobs, outcomes, storage, extractor,
                quotaGuard, limits, autoQueue(), false), jobs, quotaGuard);
        assertThat(off.workerState(quota(true, null), 2)).isEqualTo(WorkerState.DISABLED);
        WorkerStatusService noKey = new WorkerStatusService(worker(new GeminiConfig("", "gemini-3.5-flash-lite",
                "http://127.0.0.1:9", true)), jobs, quotaGuard);
        assertThat(noKey.workerState(quota(true, null), 2)).isEqualTo(WorkerState.GEMINI_KEY_MISSING);
    }

    @Test
    void theExtractorStillRefusesWithoutTheConfirmation() {
        com.actify.invoice.llm.GeminiInvoiceExtractor real = new com.actify.invoice.llm.GeminiInvoiceExtractor(
                new org.springframework.web.client.RestTemplate(), config(false),
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatThrownBy(() -> real.extract(PDF)).isInstanceOf(InvoiceExtractionException.class)
                .hasMessage(GeminiConfig.NOT_CONFIRMED_REASON);
    }
}
