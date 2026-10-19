package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.WorkflowDtos.DecisionRequest;
import com.actify.invoice.review.ApprovalService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin decisions (ADMIN-only, see SecurityConfig). Bodies:
 *   approve  {"version": 3, "reason": "optional note"}
 *   reject   {"version": 3, "reason": "required"}
 *   send-back / reopen / requeue  {"reason": "required"}
 */
@RestController
@RequestMapping("/api/invoices/{id}")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping(value = "/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse approve(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return approvalService.approve(id, body.getVersion(), body.getReason());
    }

    @PostMapping(value = "/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse reject(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return approvalService.reject(id, body.getVersion(), body.getReason());
    }

    @PostMapping(value = "/send-back", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse sendBack(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return approvalService.sendBack(id, body.getReason());
    }

    @PostMapping(value = "/reopen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse reopen(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return approvalService.reopen(id, body.getReason());
    }

    @PostMapping(value = "/requeue", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse requeue(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return approvalService.requeue(id, body.getReason());
    }
}
