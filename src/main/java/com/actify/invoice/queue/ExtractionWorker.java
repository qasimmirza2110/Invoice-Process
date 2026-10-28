package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
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
import org.springframework.util.StringUtils;

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
 *
 * FREE_TIER_TEST mode (GeminiConfig): exactly ONE Gemini call per run. After the first claimed job - success or any
 * failure - the worker halts until the next restart and reports why (getHaltReason). A failure is final (FAILED): no
 * back-off retry and no waiting for the daily reset, so the test invoice is never sent a second time automatically.
 */
@Component
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    static final long BASE_BACKOFF_SECONDS = 30;
    static final long MAX_BACKOFF_SECONDS = 15 * 60;
    static final long MAX_PROVIDER_WAIT_SECONDS = 60 * 60;

    private final ExtractionJobService jobService;
    private final ExtractionOutcomeService outcomes;
    private final InvoiceDocumentStorage documentStorage;
    private final LlmInvoiceExtractor extractor;
    private final GeminiQuotaGuard quotaGuard;
    private final QuotaSettings quotaSettings;
    private final GeminiConfig geminiConfig;
    private final boolean enabled;
    private final String workerName = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    private boolean warnedNotConfigured;
    /** Set once the free-tier test has made its one call: no further claim in this run. Never contains a secret. */
    private volatile String haltReason;

    public ExtractionWorker(ExtractionJobService jobService, ExtractionOutcomeService outcomes,
                            InvoiceDocumentStorage documentStorage, LlmInvoiceExtractor extractor,
                            GeminiQuotaGuard quotaGuard, QuotaSettings quotaSettings, GeminiConfig geminiConfig,
                            @Value("${invoice.worker.enabled:false}") boolean enabled) {
        this.jobService = jobService;
        this.outcomes = outcomes;
        this.documentStorage = documentStorage;
        this.extractor = extractor;
        this.quotaGuard = quotaGuard;
        this.quotaSettings = quotaSettings;
        this.geminiConfig = geminiConfig;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${invoice.worker.poll-interval-ms:2000}",
            initialDelayString = "${invoice.worker.initial-delay-ms:10000}")
    public void poll() {
        if (!isRunning()) {
            return;
        }
        try {
            quotaGuard.recoverInFlight(jobService.getOnlyInvoiceIds());
            jobService.recoverStaleClaims();
            processNextJob();
        } catch (RuntimeException e) {
            // Never let one bad job stop the scheduler thread.
            log.error("Extraction worker iteration failed", e);
        }
    }

    /**
     * True when the background worker actually runs (enabled AND an API key AND limits configured AND the Gemini data
     * policy allows sending documents - GeminiConfig).
     */
    public boolean isRunning() {
        if (!enabled || haltReason != null) {
            return false;
        }
        // Without a key every claimed job would fail as CONFIGURATION and end up FAILED: a setup problem must leave
        // the queue untouched (QUEUED, "waiting for extraction"), not be recorded as an extraction failure.
        if (!hasApiKey()) {
            if (!warnedNotConfigured) {
                log.warn("Extraction worker is enabled but stays idle: GEMINI_API_KEY is not set");
                warnedNotConfigured = true;
            }
            return false;
        }
        if (!quotaSettings.isConfigured()) {
            if (!warnedNotConfigured) {
                log.warn("Extraction worker is enabled but stays idle: {}", quotaSettings.getProblem());
                warnedNotConfigured = true;
            }
            return false;
        }
        // Same idea: an unconfirmed data policy leaves the queue untouched instead of failing every invoice.
        if (!geminiConfig.isDocumentSendingAllowed()) {
            if (!warnedNotConfigured) {
                log.warn("Extraction worker is enabled but stays idle: {}", geminiConfig.getBlockedReason());
                warnedNotConfigured = true;
            }
            return false;
        }
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** False while a paid Gemini project is required but not confirmed, or on conflicting confirmations (GeminiConfig). */
    public boolean isDocumentSendingAllowed() {
        return geminiConfig.isDocumentSendingAllowed();
    }

    /** Why the data policy blocks sending, or null (GeminiConfig.getBlockedReason). */
    public String getDocumentSendingBlockedReason() {
        return geminiConfig.getBlockedReason();
    }

    public GeminiConfig.Mode getGeminiMode() {
        return geminiConfig.getMode();
    }

    /** Non-null once the free-tier test has made its one Gemini call in this run. */
    public String getHaltReason() {
        return haltReason;
    }

    /** Presence only - the key itself is never returned or logged. */
    public boolean hasApiKey() {
        return StringUtils.hasText(geminiConfig.getApiKey());
    }

    /** @return true when a job was processed (success or failure), false when quota or the queue said "not now" */
    public boolean processNextJob() {
        if (haltReason != null) {
            return false;
        }
        ClaimedJob job = jobService.claimNext(workerName);
        if (job == null) {
            return false;
        }
        long start = System.nanoTime();
        String result = "UNEXPECTED_ERROR";
        try {
            byte[] pdf = documentStorage.read(job.getDocumentId(), job.getSha256());
            LlmExtractionResponse response = extractor.extract(pdf);
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.SUCCESS, 200, null, response.getTokenUsage(), elapsedMillis(start));
            outcomes.storeForReview(job.getInvoiceId(), response, elapsedMillis(start));
            result = "SUCCESS";
        } catch (InvoiceExtractionException e) {
            result = FailureClassifier.errorCode(e);
            handleProviderFailure(job, e, elapsedMillis(start));
        } catch (RuntimeException e) {
            log.error("Unexpected error while extracting invoice {}", job.getInvoiceId(), e);
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.PERMANENT_ERROR, null, "UNEXPECTED_ERROR", null,
                    elapsedMillis(start));
            outcomes.markFailed(job.getInvoiceId(), "UNEXPECTED_ERROR", "Unexpected error during processing", null,
                    elapsedMillis(start));
        } finally {
            // In a finally block so that even a failure while recording the outcome cannot allow a second call.
            if (geminiConfig.isFreeTierTest()) {
                haltReason = "Free-tier test finished: one Gemini call was made in this run for invoice "
                        + job.getInvoiceId() + " (result: " + result + "). The worker makes no further call until the "
                        + "next restart. Roll back now (docs/RENDER_DEPLOYMENT.md); another test needs a NEW upload "
                        + "with its own invoice id.";
                log.warn("Extraction worker halted: {}", haltReason);
            }
        }
        return true;
    }

    private void handleProviderFailure(ClaimedJob job, InvoiceExtractionException e, long durationMs) {
        String code = FailureClassifier.errorCode(e);
        FailureClassifier.Decision decision = FailureClassifier.classify(e);
        if (geminiConfig.isFreeTierTest()) {
            // One attempt only: whatever went wrong (key, quota, network, answer) is final - nothing is re-queued.
            Outcome outcome = decision == FailureClassifier.Decision.WAIT_FOR_DAILY_RESET ? Outcome.DAILY_QUOTA
                    : decision == FailureClassifier.Decision.RETRY ? Outcome.RETRYABLE_ERROR : Outcome.PERMANENT_ERROR;
            quotaGuard.complete(job.getGeminiAttemptId(), outcome, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            outcomes.markFailed(job.getInvoiceId(), code, e.getMessage() + " (free-tier test: no automatic retry)",
                    e.getTokenUsage(), durationMs);
            return;
        }
        if (decision == FailureClassifier.Decision.WAIT_FOR_DAILY_RESET) {
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.DAILY_QUOTA, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            outcomes.waitForDailyReset(job.getInvoiceId(), code, quotaGuard.nextDailyReset().plusMinutes(1),
                    e.getTokenUsage(), durationMs);
        } else if (decision == FailureClassifier.Decision.RETRY && job.hasAttemptsLeftAfterThis()) {
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.RETRYABLE_ERROR, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            LocalDateTime next = LocalDateTime.now().plusSeconds(retryDelaySeconds(job.getAttemptNumber(),
                    ThreadLocalRandom.current().nextDouble(), e.getRetryAfterSeconds()));
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

    /**
     * Our own back-off, but never shorter than the wait the provider asked for (Retry-After / RetryInfo). The provider's
     * hint is capped at MAX_PROVIDER_WAIT_SECONDS so one odd value cannot park a job for a day; a daily-quota 429 is
     * handled separately (wait for the reset) and never comes here.
     */
    static long retryDelaySeconds(int attemptNumber, double random, Long providerRetryAfterSeconds) {
        long ours = backoffSeconds(attemptNumber, random);
        if (providerRetryAfterSeconds == null || providerRetryAfterSeconds <= 0) {
            return ours;
        }
        return Math.max(ours, Math.min(providerRetryAfterSeconds, MAX_PROVIDER_WAIT_SECONDS));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
