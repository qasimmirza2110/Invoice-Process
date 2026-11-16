package com.actify.invoice.controller;

import com.actify.invoice.queue.WorkerStatusService;
import com.actify.invoice.quota.QuotaStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADMIN-only (/api/admin/**): is the background worker running, and is Gemini free-tier pacing pausing it?
 * Shows why the worker is idle (not enabled, not confirmed, limits not configured, minute budget used, daily quota
 * reached). Counts and codes only - never invoice content, the API key or another secret.
 */
@RestController
public class AdminQueueController {

    private final WorkerStatusService workerStatus;

    public AdminQueueController(WorkerStatusService workerStatus) {
        this.workerStatus = workerStatus;
    }

    @GetMapping("/api/admin/queue/status")
    public Map<String, Object> status() {
        QuotaStatus quota = workerStatus.quota();
        long eligible = workerStatus.eligibleQueuedInvoices();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerEnabled", workerStatus.isWorkerEnabled());
        result.put("workerRunning", workerStatus.isWorkerRunning(quota));
        result.put("geminiMode", workerStatus.geminiMode().name());
        result.put("geminiKeyConfigured", workerStatus.isGeminiKeyConfigured()); // presence only, never the value
        result.put("idleReason", workerStatus.idleReason(quota));
        result.put("quota", quota);
        result.put("workerState", workerStatus.workerState(quota, eligible).name());
        // Consented QUEUED invoices that can start now, and those waiting for a later time (back-off / quota deferral).
        result.put("eligibleQueuedInvoices", eligible);
        result.put("deferredQueuedInvoices", workerStatus.deferredQueuedInvoices());
        result.put("resumeAt", workerStatus.resumeAt(quota));
        // QUEUED invoices uploaded WITHOUT free-tier consent - never sent to Gemini.
        result.put("queuedWithoutFreeTierConsent", workerStatus.queuedWithoutFreeTierConsent());
        // Error code + invoice id + time only (e.g. "HTTP_429 (invoice 17) at ..."), never a provider message.
        result.put("lastExtractionError", workerStatus.lastExtractionError());
        return result;
    }
}
