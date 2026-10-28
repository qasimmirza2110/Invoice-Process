package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * One answer to "why is extraction not moving?", shared by GET /api/admin/queue/status and the admin intake board.
 * Read-only: it never starts the worker and never calls Gemini.
 */
@Service
public class WorkerStatusService {

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

    /** The invoices the worker is limited to (a controlled test run); empty = all queued invoices. */
    public Set<Long> onlyInvoiceIds() {
        return jobService.getOnlyInvoiceIds();
    }

    public QuotaStatus quota() {
        return quotaGuard.status();
    }

    /** The data-policy mode (NOT_CONFIRMED, PAID, FREE_TIER_TEST, LOCAL_DEMO, CONFLICT) - never secret. */
    public GeminiConfig.Mode geminiMode() {
        return worker.getGeminiMode();
    }

    /**
     * true when the worker may actually pick up jobs (enabled AND a key AND limits AND data policy AND not halted
     * after the free-tier test's one call).
     */
    public boolean isWorkerRunning(QuotaStatus quota) {
        return worker.isEnabled() && worker.hasApiKey() && quota.isConfigured() && worker.isDocumentSendingAllowed()
                && worker.getHaltReason() == null;
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
        if (worker.getHaltReason() != null) {
            return worker.getHaltReason();
        }
        if (!quota.isConfigured()) {
            return "Gemini limits are not configured: " + quota.getConfigurationProblem();
        }
        if (quota.getPausedUntil() != null) {
            return "Daily Gemini quota reached; paused until " + quota.getPausedUntil() + ".";
        }
        return jobService.getLastQuotaReason();
    }
}
