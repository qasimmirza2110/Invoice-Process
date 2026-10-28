package com.actify.invoice.controller;

import com.actify.invoice.queue.WorkerStatusService;
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

    private final WorkerStatusService workerStatus;

    public AdminQueueController(WorkerStatusService workerStatus) {
        this.workerStatus = workerStatus;
    }

    @GetMapping("/api/admin/queue/status")
    public Map<String, Object> status() {
        QuotaStatus quota = workerStatus.quota();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerEnabled", workerStatus.isWorkerEnabled());
        result.put("workerRunning", workerStatus.isWorkerRunning(quota));
        result.put("geminiMode", workerStatus.geminiMode().name());
        result.put("geminiKeyConfigured", workerStatus.isGeminiKeyConfigured()); // presence only, never the value
        result.put("onlyInvoiceIds", workerStatus.onlyInvoiceIds());
        result.put("idleReason", workerStatus.idleReason(quota));
        result.put("quota", quota);
        return result;
    }
}
