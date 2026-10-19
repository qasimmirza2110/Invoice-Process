package com.actify.invoice.controller;

import com.actify.invoice.queue.ExtractionJobService;
import com.actify.invoice.queue.ExtractionWorker;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADMIN-only (/api/admin/**): is the background worker running, and is Gemini pacing pausing it?
 * Shows why the worker is idle (not enabled, limits not configured, minute budget used, daily quota reached).
 */
@RestController
public class AdminQueueController {

    private final ExtractionWorker worker;
    private final ExtractionJobService jobService;
    private final GeminiQuotaGuard quotaGuard;

    public AdminQueueController(ExtractionWorker worker, ExtractionJobService jobService, GeminiQuotaGuard quotaGuard) {
        this.worker = worker;
        this.jobService = jobService;
        this.quotaGuard = quotaGuard;
    }

    @GetMapping("/api/admin/queue/status")
    public Map<String, Object> status() {
        QuotaStatus quota = quotaGuard.status();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerEnabled", worker.isEnabled());
        result.put("workerRunning", worker.isEnabled() && quota.isConfigured());
        result.put("idleReason", idleReason(quota));
        result.put("quota", quota);
        return result;
    }

    private String idleReason(QuotaStatus quota) {
        if (!worker.isEnabled()) {
            return "The extraction worker is switched off (INVOICE_WORKER_ENABLED is not true).";
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
