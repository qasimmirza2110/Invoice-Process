package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * One answer to "why is extraction not moving?", shared by GET /api/admin/queue/status and the admin intake board.
 * Read-only: it never starts the worker and never calls Gemini.
 */
@Service
public class WorkerStatusService {

    /**
     * A machine-readable summary of the worker (GET /api/admin/queue/status "workerState"); idleReason explains it.
     * Checked in this order - the first that applies wins.
     */
    public enum WorkerState {
        /** INVOICE_WORKER_ENABLED is not true. */
        DISABLED,
        /** GEMINI_API_KEY is not set (presence only - a set key may still be invalid). */
        GEMINI_KEY_MISSING,
        /** INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED is not true: nothing is sent. */
        MODE_NOT_CONFIRMED,
        /** GEMINI_LIMIT_* values are missing or invalid. */
        LIMITS_NOT_CONFIGURED,
        /** The daily budget is used or Gemini reported its daily quota: waiting for midnight Pacific time. */
        DAILY_QUOTA_PAUSED,
        /** The last worker iteration failed unexpectedly (see the server log). */
        WORKER_ERROR,
        /** The last claim was refused by the per-minute pacing, the TPM budget or a 429 cool-down. */
        WAITING_FOR_QUOTA,
        /** Running, and nothing in the queue can start now. */
        IDLE_NO_ELIGIBLE_INVOICE,
        /** Running, and at least one queued invoice can start now. */
        PROCESSING_AVAILABLE
    }

    private final ExtractionWorker worker;
    private final ExtractionJobService jobService;
    private final GeminiQuotaGuard quotaGuard;

    public WorkerStatusService(ExtractionWorker worker, ExtractionJobService jobService, GeminiQuotaGuard quotaGuard) {
        this.worker = worker;
        this.jobService = jobService;
        this.quotaGuard = quotaGuard;
    }

    public boolean isWorkerEnabled() {
        return worker.isEnabled();
    }

    /** Presence of GEMINI_API_KEY only. */
    public boolean isGeminiKeyConfigured() {
        return worker.hasApiKey();
    }

    public QuotaStatus quota() {
        return quotaGuard.status();
    }

    /** NOT_CONFIRMED or FREE_TIER_AUTO_QUEUE - never secret. */
    public GeminiConfig.Mode geminiMode() {
        return worker.getGeminiMode();
    }

    /** QUEUED invoices with free-tier consent that could start now. */
    public long eligibleQueuedInvoices() {
        return jobService.countEligibleNow();
    }

    /** QUEUED invoices with free-tier consent waiting for a later time (back-off, 429 or daily-quota deferral). */
    public long deferredQueuedInvoices() {
        return jobService.countDeferred();
    }

    /** QUEUED invoices without the uploader's free-tier consent (never sent). */
    public long queuedWithoutFreeTierConsent() {
        return jobService.countQueuedWithoutFreeTierConsent();
    }

    /** Error code and time of the last failed extraction, or null - never a message, a key or invoice content. */
    public String lastExtractionError() {
        return worker.getLastExtractionError();
    }

    /** true when the worker may actually pick up jobs (enabled AND a key AND limits AND the free-tier confirmation). */
    public boolean isWorkerRunning(QuotaStatus quota) {
        return worker.isEnabled() && worker.hasApiKey() && quota.isConfigured() && worker.isDocumentSendingAllowed();
    }

    public WorkerState workerState(QuotaStatus quota, long eligibleQueuedInvoices) {
        if (!worker.isEnabled()) {
            return WorkerState.DISABLED;
        }
        if (!worker.hasApiKey()) {
            return WorkerState.GEMINI_KEY_MISSING;
        }
        if (!worker.isDocumentSendingAllowed()) {
            return WorkerState.MODE_NOT_CONFIRMED;
        }
        if (!quota.isConfigured()) {
            return WorkerState.LIMITS_NOT_CONFIGURED;
        }
        if (quota.getPausedUntil() != null) {
            return WorkerState.DAILY_QUOTA_PAUSED;
        }
        if (worker.getLastIterationError() != null) {
            return WorkerState.WORKER_ERROR;
        }
        if (jobService.getLastQuotaReason() != null || quota.getRateLimitCooldownUntil() != null) {
            return WorkerState.WAITING_FOR_QUOTA;
        }
        return eligibleQueuedInvoices > 0 ? WorkerState.PROCESSING_AVAILABLE : WorkerState.IDLE_NO_ELIGIBLE_INVOICE;
    }

    /** When a known quota wait ends (daily pause, 429 cool-down or pacing), or null when none is known. */
    public LocalDateTime resumeAt(QuotaStatus quota) {
        if (quota.getPausedUntil() != null) {
            return quota.getPausedUntil();
        }
        if (quota.getRateLimitCooldownUntil() != null) {
            return quota.getRateLimitCooldownUntil();
        }
        return jobService.getLastQuotaReason() == null ? null : jobService.getLastQuotaNotBefore();
    }

    /** Why queued jobs are not being processed right now, or null/the last pacing reason when the worker runs. */
    public String idleReason(QuotaStatus quota) {
        if (!worker.isEnabled()) {
            return "The extraction worker is switched off (INVOICE_WORKER_ENABLED is not true)"
                    + (worker.hasApiKey() ? "." : " and no Gemini API key is configured - extraction is not set up "
                    + "in this run.");
        }
        if (!worker.hasApiKey()) {
            return "Extraction is not configured: GEMINI_API_KEY is not set, so the worker stays idle.";
        }
        if (!worker.isDocumentSendingAllowed()) {
            return worker.getDocumentSendingBlockedReason();
        }
        if (!quota.isConfigured()) {
            return "Gemini limits are not configured: " + quota.getConfigurationProblem();
        }
        if (quota.getPausedUntil() != null) {
            return "Daily Gemini quota (RPD) reached; waiting for the quota reset at " + quota.getPausedUntil()
                    + " (midnight Pacific time). Queued invoices are kept and resume automatically while the service runs.";
        }
        if (worker.getLastIterationError() != null) {
            return "The last worker iteration failed (" + worker.getLastIterationError() + "); see the server log. "
                    + "The worker keeps polling.";
        }
        if (jobService.getLastQuotaReason() == null && quota.getRateLimitCooldownUntil() != null) {
            return "Gemini answered 429 (rate limit); cooling down until " + quota.getRateLimitCooldownUntil() + ".";
        }
        return jobService.getLastQuotaReason();
    }
}
