package com.actify.invoice.queue;

import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.entity.GeminiCallAttempt.Outcome;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.quota.FailureClassifier;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.queue.ExtractionJobService.ClaimedJob;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single background worker of the queue. Every few seconds (one thread, one job at a time):
 *   1. claimNext()  - short transaction: quota check + oldest eligible job -> PROCESSING + attempt row (commit)
 *   2. read the stored PDF and call the LLM - NO transaction is open during the slow call
 *   3. record the attempt's outcome, then: NEEDS_REVIEW, retry later, wait for the daily reset, or FAILED
 *
 * Retries (bounded, only for transient errors - see FailureClassifier):
 *   attempt 1 fails -> wait ~30 s; attempt 2 fails -> wait ~2 min; ... (x4 each time, +-20 % jitter, max 15 min);
 *   after max-attempts (default 3) the invoice is FAILED. A daily-quota 429 does not use up an attempt.
 *
 * OFF unless invoice.worker.enabled=true AND the Gemini limits are configured (QuotaSettings). Tests call
 * processNextJob() directly.
 */
@Component
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    static final long BASE_BACKOFF_SECONDS = 30;
    static final long MAX_BACKOFF_SECONDS = 15 * 60;

    private final ExtractionJobService jobService;
    private final ExtractionOutcomeService outcomes;
    private final InvoiceDocumentStorage documentStorage;
    private final LlmInvoiceExtractor extractor;
    private final GeminiQuotaGuard quotaGuard;
    private final QuotaSettings quotaSettings;
    private final boolean enabled;
    private final String workerName = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    private boolean warnedNotConfigured;

    public ExtractionWorker(ExtractionJobService jobService, ExtractionOutcomeService outcomes,
                            InvoiceDocumentStorage documentStorage, LlmInvoiceExtractor extractor,
                            GeminiQuotaGuard quotaGuard, QuotaSettings quotaSettings,
                            @Value("${invoice.worker.enabled:false}") boolean enabled) {
        this.jobService = jobService;
        this.outcomes = outcomes;
        this.documentStorage = documentStorage;
        this.extractor = extractor;
        this.quotaGuard = quotaGuard;
        this.quotaSettings = quotaSettings;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${invoice.worker.poll-interval-ms:2000}",
            initialDelayString = "${invoice.worker.initial-delay-ms:10000}")
    public void poll() {
        if (!isRunning()) {
            return;
        }
        try {
            quotaGuard.recoverInFlight();
            jobService.recoverStaleClaims();
            processNextJob();
        } catch (RuntimeException e) {
            // Never let one bad job stop the scheduler thread.
            log.error("Extraction worker iteration failed", e);
        }
    }

    /** True when the background worker actually runs (enabled AND limits configured). */
    public boolean isRunning() {
        if (!enabled) {
            return false;
        }
        if (!quotaSettings.isConfigured()) {
            if (!warnedNotConfigured) {
                log.warn("Extraction worker is enabled but stays idle: {}", quotaSettings.getProblem());
                warnedNotConfigured = true;
            }
            return false;
        }
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return true when a job was processed (success or failure), false when quota or the queue said "not now" */
    public boolean processNextJob() {
        ClaimedJob job = jobService.claimNext(workerName);
        if (job == null) {
            return false;
        }
        long start = System.nanoTime();
        try {
            byte[] pdf = documentStorage.read(job.getDocumentId(), job.getSha256());
            LlmExtractionResponse response = extractor.extract(pdf);
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.SUCCESS, 200, null, response.getTokenUsage(), elapsedMillis(start));
            outcomes.storeForReview(job.getInvoiceId(), response, elapsedMillis(start));
        } catch (InvoiceExtractionException e) {
            handleProviderFailure(job, e, elapsedMillis(start));
        } catch (RuntimeException e) {
            log.error("Unexpected error while extracting invoice {}", job.getInvoiceId(), e);
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.PERMANENT_ERROR, null, "UNEXPECTED_ERROR", null,
                    elapsedMillis(start));
            outcomes.markFailed(job.getInvoiceId(), "UNEXPECTED_ERROR", "Unexpected error during processing", null,
                    elapsedMillis(start));
        }
        return true;
    }

    private void handleProviderFailure(ClaimedJob job, InvoiceExtractionException e, long durationMs) {
        String code = FailureClassifier.errorCode(e);
        FailureClassifier.Decision decision = FailureClassifier.classify(e);
        if (decision == FailureClassifier.Decision.WAIT_FOR_DAILY_RESET) {
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.DAILY_QUOTA, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            outcomes.waitForDailyReset(job.getInvoiceId(), code, quotaGuard.nextDailyReset().plusMinutes(1),
                    e.getTokenUsage(), durationMs);
        } else if (decision == FailureClassifier.Decision.RETRY && job.hasAttemptsLeftAfterThis()) {
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.RETRYABLE_ERROR, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            LocalDateTime next = LocalDateTime.now().plusSeconds(backoffSeconds(job.getAttemptNumber(),
                    ThreadLocalRandom.current().nextDouble()));
            outcomes.scheduleRetry(job.getInvoiceId(), code, e.getMessage(), next, e.getTokenUsage(), durationMs);
        } else {
            quotaGuard.complete(job.getGeminiAttemptId(),
                    decision == FailureClassifier.Decision.RETRY ? Outcome.RETRYABLE_ERROR : Outcome.PERMANENT_ERROR,
                    e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            String message = decision == FailureClassifier.Decision.RETRY
                    ? e.getMessage() + " (gave up after " + job.getAttemptNumber() + " attempts)"
                    : e.getMessage();
            outcomes.markFailed(job.getInvoiceId(), code, message, e.getTokenUsage(), durationMs);
        }
    }

    /**
     * 30 s, 2 min, 8 min, ... (x4 per attempt), capped at 15 min, with +-20 % jitter so retries of many
     * jobs do not all fire in the same second.
     *
     * @param random a value in [0, 1) - a parameter so tests can check the exact bounds
     */
    static long backoffSeconds(int attemptNumber, double random) {
        double base = BASE_BACKOFF_SECONDS * Math.pow(4, Math.max(0, attemptNumber - 1));
        double capped = Math.min(base, MAX_BACKOFF_SECONDS);
        double jitter = 0.8 + 0.4 * random;
        return Math.max(1, Math.round(capped * jitter));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
