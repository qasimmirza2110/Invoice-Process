package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.dto.WorkflowDtos.AssignmentRequest;
import com.actify.invoice.dto.WorkflowDtos.AssignmentResult;
import com.actify.invoice.review.ReviewAssignmentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** ADMIN-only (/api/admin/**): reviewer categories, invoice categories and reviewer assignments. */
@RestController
public class AdminReviewController {

    private final ReviewAssignmentService assignments;

    public AdminReviewController(ReviewAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/api/admin/users/{id}/categories")
    public List<String> reviewerCategories(@PathVariable Long id) {
        return assignments.reviewerCategories(id);
    }

    /** Body: {"categories": ["ELECTRONICS", "FURNITURE"]} - replaces the reviewer's list. */
    @PutMapping(value = "/api/admin/users/{id}/categories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<String> setReviewerCategories(@PathVariable Long id, @RequestBody Map<String, List<String>> body) {
        return assignments.setReviewerCategories(id, body.get("categories"));
    }

    /** Body: {"categoryCode": "FURNITURE"} (or null to clear). */
    @PutMapping(value = "/api/admin/invoices/{id}/category", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse setInvoiceCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return assignments.setInvoiceCategory(id, body.get("categoryCode"));
    }

    /** Body: {"invoiceIds": [41, 42], "reviewerId": 7}. reviewerId null = unassign. One result per invoice. */
    @PostMapping(value = "/api/admin/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<AssignmentResult> assign(@RequestBody AssignmentRequest body) {
        return assignments.assign(body.getInvoiceIds(), body.getReviewerId());
    }
}
