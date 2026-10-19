package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.WorkflowDtos.DecisionRequest;
import com.actify.invoice.dto.WorkflowDtos.EventResponse;
import com.actify.invoice.dto.WorkflowDtos.InvoiceSummary;
import com.actify.invoice.dto.WorkflowDtos.ReviewReasonResponse;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.review.InvoiceListService;
import com.actify.invoice.review.ReviewService;
import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.validation.ReviewReason;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lists and review endpoints. Reads are scoped per user (InvoiceListService / InvoiceAccessPolicy: 404 for invoices
 * you may not see); submit is REVIEWER-only (SecurityConfig) and only for the assigned reviewer (ReviewService).
 */
@RestController
@RequestMapping("/api/invoices")
public class ReviewController {

    private final InvoiceListService listService;
    private final ReviewService reviewService;
    private final InvoiceAccessPolicy accessPolicy;

    public ReviewController(InvoiceListService listService, ReviewService reviewService, InvoiceAccessPolicy accessPolicy) {
        this.listService = listService;
        this.reviewService = reviewService;
        this.accessPolicy = accessPolicy;
    }

    /** Vendor: own uploads. Reviewer: assigned to me. Admin: all workflow invoices (legacy=true: the old ones). */
    @GetMapping
    public List<InvoiceSummary> list(@RequestParam(value = "status", required = false) InvoiceStatus status,
                                     @RequestParam(value = "category", required = false) String category,
                                     @RequestParam(value = "batchId", required = false) Long batchId,
                                     @RequestParam(value = "unassigned", required = false) Boolean unassigned,
                                     @RequestParam(value = "legacy", defaultValue = "false") boolean legacy,
                                     @RequestParam(value = "page", defaultValue = "0") int page,
                                     @RequestParam(value = "size", defaultValue = "50") int size) {
        return listService.list(status, category, batchId, unassigned, legacy, page, size);
    }

    /** Why the extraction needs attention (stored at extraction time, V1). */
    @GetMapping("/{id}/review-reasons")
    public List<ReviewReasonResponse> reviewReasons(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return reviewService.reviewReasons(id);
    }

    /** The automatic checks on the CURRENT data (after corrections). Empty list = everything passes. */
    @GetMapping("/{id}/checks")
    public List<ReviewReason> currentChecks(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return reviewService.currentChecks(id);
    }

    /** The append-only status history: who moved the invoice, when, from/to, which version, why. */
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@PathVariable Long id) {
        accessPolicy.requireReadableInvoice(id);
        return reviewService.events(id);
    }

    /** Body: {"version": 3}. 409 if 3 is not the current version; 422 if the current data fails a check. */
    @PostMapping(value = "/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse submit(@PathVariable Long id, @RequestBody DecisionRequest body) {
        accessPolicy.requireReadableInvoice(id);
        return reviewService.submit(id, body.getVersion());
    }
}
