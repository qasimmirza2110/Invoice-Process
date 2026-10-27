package com.actify.invoice.controller;

import com.actify.invoice.cloud.CloudSourceSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GET /api/cloud-sources (VENDOR, ADMIN): which cloud pickers can be offered and their honest status.
 * Read-only; it never calls Google or Microsoft. File bytes still arrive through POST /api/batches/{id}/files.
 */
@RestController
@RequestMapping("/api/cloud-sources")
public class CloudSourceController {

    private final CloudSourceSettings settings;

    public CloudSourceController(CloudSourceSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return settings.describeAll();
    }
}
