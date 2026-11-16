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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single background worker of the free-tier queue. Every few seconds (one thread, one job at a time):
 *   1. claimNext()  - short transaction: oldest QUEUED invoice WITH free-tier consent + quota check -> PROCESSING +
 *                     attempt row (commit)
 *   2. re-check the consent, read the stored PDF and call Gemini - NO transaction is open during the slow call
 *   3. record the attempt's outcome and token usage, then: NEEDS_REVIEW, retry later, wait for the quota, or FAILED
 *
 * Quota (GeminiQuotaGuard): when RPM, TPM or RPD is used up nothing is claimed - every invoice stays QUEUED and the
 * next poll looks again (no Gemini call is made while waiting). A per-minute 429 from Gemini puts the invoice back in
 * the queue WITHOUT using one of its attempts and cools the whole queue down for at least 60 s (or the provider's
 * Retry-After); a daily-quota 429 parks it until midnight Pacific time. Neither is ever retried in a tight loop and
 * neither ever switches to another model, provider or paid project - there is none.
 *
 * Other transient errors (5xx, network): bounded retries with back-off (30 s, 2 min, ... +-20 % jitter, max 15 min);
 * after max-attempts (default 3) the invoice is FAILED.
 *
 * Restarts (deploy, crash, Render Free spin-down): the queue lives in PostgreSQL. On startup and on every poll the
 * worker recovers interrupted claims and calls, then continues with the oldest eligible invoice. While the service is
 * asleep nothing runs; the next request that wakes it also restarts the worker.
 *
 * OFF unless invoice.worker.enabled=true AND a key AND the Gemini limits AND the free-tier confirmation. Tests call
 * processNextJob() directly.
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
    /** Exception class and time of the last failed poll (no message: it could carry details), or null. */
    private volatile String lastIterationError;
    /** Error code of the last failed extraction (e.g. HTTP_429, INVALID_RESPONSE) and when - never a message. */
    private volatile String lastExtractionError;

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

    /**
     * Startup (including a Render wake-up): recover what a previous process left behind and report the waiting queue.
     * The scheduled poll() then resumes processing on its own - no invoice id is ever configured.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!isRunning()) {
            return;
        }
        try {
            quotaGuard.recoverInFlight();
            int recovered = jobService.recoverStaleClaims();
            log.info("event=queue_startup eligibleNow={} deferred={} withoutConsent={} recoveredClaims={}",
                    jobService.countEligibleNow(), jobService.countDeferred(),
                    jobService.countQueuedWithoutFreeTierConsent(), recovered);
        } catch (RuntimeException e) {
            lastIterationError = e.getClass().getSimpleName() + " at " + LocalDateTime.now();
            log.error("Extraction worker startup recovery failed", e);
        }
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
            lastIterationError = null;
        } catch (RuntimeException e) {
            // Never let one bad job stop the scheduler thread.
            lastIterationError = e.getClass().getSimpleName() + " at " + LocalDateTime.now();
            log.error("Extraction worker iteration failed", e);
        }
    }

    /**
     * True when the background worker actually runs (enabled AND an API key AND limits configured AND the free-tier
     * queue confirmed - GeminiConfig).
     */
    public boolean isRunning() {
        if (!enabled) {
            return false;
        }
        // Without a key every claimed job would fail as CONFIGURATION and end up FAILED: a setup problem must leave
        // the queue untouched (QUEUED, "waiting for extraction"), not be recorded as an extraction failure.
        if (!hasApiKey()) {
            warnOnce("GEMINI_API_KEY is not set");
            return false;
        }
        if (!quotaSettings.isConfigured()) {
            warnOnce(quotaSettings.getProblem());
            return false;
        }
        // Same idea: an unconfirmed free-tier queue leaves the queue untouched instead of failing every invoice.
        if (!geminiConfig.isDocumentSendingAllowed()) {
            warnOnce(geminiConfig.getBlockedReason());
            return false;
        }
        return true;
    }

    private void warnOnce(String reason) {
        if (!warnedNotConfigured) {
            log.warn("Extraction worker is enabled but stays idle: {}", reason);
            warnedNotConfigured = true;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** False until the free-tier queue is confirmed (GeminiConfig). */
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

    /** Non-null while the last poll failed with an unexpected error (exception class and time only). */
    public String getLastIterationError() {
        return lastIterationError;
    }

    /** Error code and time of the last failed extraction, or null (never a provider message or invoice content). */
    public String getLastExtractionError() {
        return lastExtractionError;
    }

    /** Presence only - the key itself is never returned or logged. */
    public boolean hasApiKey() {
        return StringUtils.hasText(geminiConfig.getApiKey());
    }

    /** @return true when a job was processed (success or failure), false when quota or the queue said "not now" */
    public boolean processNextJob() {
        ClaimedJob job = jobService.claimNext(workerName);
        if (job == null) {
            return false;
        }
        long start = System.nanoTime();
        String result = "UNEXPECTED_ERROR";
        try {
            // Last check immediately before the PDF leaves the server (consent is immutable; this guards against bugs).
            if (!jobService.hasFreeTierConsent(job.getInvoiceId())) {
                result = "NO_FREE_TIER_CONSENT";
                log.error("Invoice {} lost its free-tier consent after the claim; it is not sent", job.getInvoiceId());
                quotaGuard.complete(job.getGeminiAttemptId(), Outcome.PERMANENT_ERROR, null, result, null,
                        elapsedMillis(start));
                outcomes.markFailed(job.getInvoiceId(), result, "Not sent to Gemini: the upload has no free-tier consent.",
                        null, elapsedMillis(start));
                return true;
            }
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
            if (!"SUCCESS".equals(result)) {
                lastExtractionError = result + " (invoice " + job.getInvoiceId() + ") at " + LocalDateTime.now();
            }
            // One line per Gemini call: ids, mode, classified result and time only - never the key or invoice content.
            log.info("event=extraction_attempt invoiceId={} attempt={} geminiAttemptId={} mode={} result={} elapsedMs={}",
                    job.getInvoiceId(), job.getAttemptNumber(), job.getGeminiAttemptId(), geminiConfig.getMode(), result,
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
        } else if (decision == FailureClassifier.Decision.RETRY && Integer.valueOf(429).equals(e.getHttpStatus())) {
            // A busy free-tier minute must not turn invoices into FAILED: back to the queue, attempt not used up.
            quotaGuard.complete(job.getGeminiAttemptId(), Outcome.RETRYABLE_ERROR, e.getHttpStatus(), code, e.getTokenUsage(), durationMs);
            LocalDateTime next = LocalDateTime.now().plusSeconds(rateLimitDelaySeconds(e.getRetryAfterSeconds()));
            log.warn("event=rate_limited invoiceId={} code={} deferredUntil={}", job.getInvoiceId(), code, next);
            outcomes.deferForRateLimit(job.getInvoiceId(), code, next, e.getTokenUsage(), durationMs);
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

    /**
     * Per-minute 429: wait at least the guard's cool-down, longer when the provider asked for it (capped at
     * MAX_PROVIDER_WAIT_SECONDS like any provider hint).
     */
    static long rateLimitDelaySeconds(Long providerRetryAfterSeconds) {
        long wait = GeminiQuotaGuard.RATE_LIMIT_COOLDOWN_SECONDS;
        if (providerRetryAfterSeconds != null && providerRetryAfterSeconds > 0) {
            wait = Math.max(wait, Math.min(providerRetryAfterSeconds, MAX_PROVIDER_WAIT_SECONDS));
        }
        return wait;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
