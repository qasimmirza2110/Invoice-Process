package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 4 + 5 end to end on the TEST database: category routing, admin assignment, reviewer-only corrections
 * (V2 with changed_by), the submit gate, approval pinned to the exact submitted version, reasons for
 * reject/send-back/reopen, admin requeue, and the append-only event trail.
 */
class ReviewApprovalPgIntegrationTest extends AbstractQueuePgTest {

    private AppUser vendor;
    private AppUser reviewer;
    private AppUser otherReviewer;
    private String category;

    @BeforeEach
    void setUp() throws Exception {
        drainQueue();
        vendor = createUser(Role.VENDOR);
        reviewer = createUser(Role.REVIEWER);
        otherReviewer = createUser(Role.REVIEWER);
        category = "PGT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/admin/categories", "{\"code\":\"" + category + "\",\"displayName\":\"PG test\"}")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(HttpMethod.PUT, "/api/admin/users/" + reviewer.getId() + "/categories",
                "{\"categories\":[\"" + category + "\"]}").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @AfterEach
    void leaveTheQueueEmpty() {
        drainQueue();
    }

    @Test
    void fullReviewAndApprovalFlowWithEveryGuard() throws Exception {
        long id = extractedForReview(totalMismatch());

        // ---- admin routing: category first, then only a reviewer of that category
        actAs(testAdmin());
        JsonNode noCategory = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + reviewer.getId() + "}").getBody());
        assertThat(noCategory.get(0).get("assigned").asBoolean()).isFalse();
        assertThat(noCategory.get(0).get("message").asText()).contains("category first");

        assertThat(send(HttpMethod.PUT, "/api/admin/invoices/" + id + "/category", "{\"categoryCode\":\"" + category + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode wrongReviewer = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + otherReviewer.getId() + "}").getBody());
        assertThat(wrongReviewer.get(0).get("assigned").asBoolean()).isFalse();
        JsonNode assigned = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + reviewer.getId() + "}").getBody());
        assertThat(assigned.get(0).get("assigned").asBoolean()).isTrue();
        // An admin never edits a workflow invoice directly.
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(1, "118.00")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ---- another reviewer cannot see or touch it
        actAs(otherReviewer);
        assertThat(restTemplate.getForEntity("/api/invoices/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(1, "118.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(restTemplate.getForObject("/api/invoices", String.class)).size()).isZero();

        // ---- the assigned reviewer sees why, cannot submit invalid data, corrects, then submits
        actAs(reviewer);
        assertThat(json(restTemplate.getForObject("/api/invoices", String.class)).get(0).get("id").asLong()).isEqualTo(id);
        assertThat(restTemplate.getForObject("/api/invoices/" + id + "/review-reasons", String.class)).contains("TOTAL_MISMATCH");
        assertThat(restTemplate.getForObject("/api/invoices/" + id + "/checks", String.class)).contains("TOTAL_MISMATCH");
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":1}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(1, "118.00")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select changed_by from invoice_versions where invoice_id = ? and version_number = 2",
                String.class, id)).isEqualTo("user:" + reviewer.getId());
        assertThat(restTemplate.getForObject("/api/invoices/" + id + "/checks", String.class)).isEqualTo("[]");

        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":1}").getStatusCode())
                .as("stale version").isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":2}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select submitted_version from invoices where id = ?", Integer.class, id)).isEqualTo(2);
        // While waiting for approval nothing can change.
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(2, "118.00")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/approve", "{\"version\":2}").getStatusCode())
                .as("reviewers cannot approve").isEqualTo(HttpStatus.FORBIDDEN);

        // ---- admin decision pinned to the exact version
        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/approve", "{\"version\":1}").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/approve", "{\"version\":2,\"reason\":\"ok\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("APPROVED");

        // ---- reopen needs a reason; the old approval stays in the history
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/reopen", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/reopen", "{\"reason\":\"Supplier sent a credit note\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("NEEDS_REVIEW");

        // ---- resubmit, then reject (reason required)
        actAs(reviewer);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":2}").getStatusCode()).isEqualTo(HttpStatus.OK);
        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/reject", "{\"version\":2}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/reject", "{\"version\":2,\"reason\":\"Duplicate of an earlier invoice\"}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(events(id)).containsExactly(
                "null->QUEUED by user:" + vendor.getId(),
                "QUEUED->PROCESSING by system",
                "PROCESSING->NEEDS_REVIEW by system",
                "NEEDS_REVIEW->PENDING_APPROVAL by user:" + reviewer.getId(),
                "PENDING_APPROVAL->APPROVED by user:" + testAdmin().getId(),
                "APPROVED->NEEDS_REVIEW by user:" + testAdmin().getId(),
                "NEEDS_REVIEW->PENDING_APPROVAL by user:" + reviewer.getId(),
                "PENDING_APPROVAL->REJECTED by user:" + testAdmin().getId());
        assertThat(jdbc.queryForObject("select version_number from invoice_workflow_events where invoice_id = ? "
                + "and to_status = 'APPROVED'", Integer.class, id)).isEqualTo(2);

        // ---- the vendor follows the status of their own invoice
        actAs(vendor);
        assertThat(restTemplate.getForObject("/api/invoices/" + id + "/events", String.class)).contains("REJECTED");
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":2}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sendBackReturnsTheInvoiceToTheReviewerAndClearsTheSubmission() throws Exception {
        long id = extractedForReview(validResult(unique("SB")));
        assignToReviewer(id);
        actAs(reviewer);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/submit", "{\"version\":1}").getStatusCode()).isEqualTo(HttpStatus.OK);

        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/send-back", "{\"reason\":\"Check the tax id\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select submitted_version from invoices where id = ?", Integer.class, id)).isNull();
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/approve", "{\"version\":1}").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anAdminCanRequeueAFailedInvoiceWithAReason() throws Exception {
        actAs(vendor);
        long id = queueOneInvoice("requeue");
        extractorThrows(new InvoiceExtractionException("Gemini API key is not configured.",
                InvoiceExtractionException.Kind.CONFIGURATION, null, null, null, null));
        worker.processNextJob();
        assertThat(status(id)).isEqualTo("FAILED");

        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/requeue", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + id + "/requeue", "{\"reason\":\"API key fixed\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, id)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Vendor uploads; the (mocked) extraction runs; the invoice waits in NEEDS_REVIEW. */
    private long extractedForReview(InvoiceExtractionResult result) throws Exception {
        actAs(vendor);
        long id = queueOneInvoice("review");
        extractorReturns(result, null);
        assertThat(worker.processNextJob()).isTrue();
        assertThat(status(id)).isEqualTo("NEEDS_REVIEW");
        return id;
    }

    private void assignToReviewer(long id) {
        actAs(testAdmin());
        send(HttpMethod.PUT, "/api/admin/invoices/" + id + "/category", "{\"categoryCode\":\"" + category + "\"}");
        send(HttpMethod.POST, "/api/admin/assignments", "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + reviewer.getId() + "}");
    }

    private static InvoiceExtractionResult totalMismatch() {
        InvoiceExtractionResult r = validResult(unique("MISMATCH"));
        r.setTotalAmount(new BigDecimal("999.00"));
        return r;
    }

    private static String correction(int baseVersion, String total) {
        return "{\"baseVersion\":" + baseVersion + ",\"invoiceNumber\":\"" + unique("FIXED") + "\",\"currency\":\"INR\","
                + "\"subtotal\":\"100.00\",\"taxAmount\":\"18.00\",\"totalAmount\":\"" + total + "\",\"changeReason\":\"total corrected\","
                + "\"items\":[{\"description\":\"Item\",\"quantity\":\"1\",\"unitPrice\":\"100.00\",\"taxRate\":\"18\",\"lineTotal\":\"100.00\"}]}";
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(jsonBody, headers), String.class);
    }
}
