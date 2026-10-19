package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceSaveResponse;
import com.actify.invoice.dto.InvoiceUpdateRequest;
import com.actify.invoice.dto.InvoiceVersionResponse;
import com.actify.invoice.dto.VersionComparisonResponse;
import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.service.InvoiceEditService;
import com.actify.invoice.service.InvoiceVersionQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Correcting an extracted invoice and reading its version history.
 * PUT: the assigned reviewer (workflow invoices) or an admin (legacy invoices) - see InvoiceAccessPolicy.requireCanCorrect.
 * Every read asks InvoiceAccessPolicy first (404 for other users' invoices).
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceVersionController {

    private final InvoiceEditService editService;
    private final InvoiceVersionQueryService queryService;
    private final InvoiceAccessPolicy accessPolicy;

    public InvoiceVersionController(InvoiceEditService editService, InvoiceVersionQueryService queryService,
                                    InvoiceAccessPolicy accessPolicy) {
        this.editService = editService;
        this.queryService = queryService;
        this.accessPolicy = accessPolicy;
    }

    /** Saves the complete corrected invoice as a new version. 409 when baseVersion is not the latest version. */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceSaveResponse saveCorrection(@PathVariable Long id, @RequestBody InvoiceUpdateRequest request) {
        accessPolicy.requireCanCorrect(id);
        return editService.saveCorrection(id, request);
    }

    @GetMapping("/{id}/versions")
    public List<InvoiceVersionResponse> listVersions(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return queryService.listVersions(id);
    }

    // Declared before /{versionNumber} only for readability; "compare" is not numeric, so they never clash.
    @GetMapping("/{id}/versions/compare")
    public VersionComparisonResponse compare(@PathVariable Long id, @RequestParam("from") int from,
                                             @RequestParam("to") int to) {
        accessPolicy.requireReadableInvoice(id);
        return queryService.compare(id, from, to);
    }

    @GetMapping("/{id}/versions/{versionNumber:\\d+}")
    public InvoiceVersionResponse getVersion(@PathVariable Long id, @PathVariable int versionNumber) {
        accessPolicy.requireReadableInvoice(id);
        return queryService.getVersion(id, versionNumber);
    }
}
