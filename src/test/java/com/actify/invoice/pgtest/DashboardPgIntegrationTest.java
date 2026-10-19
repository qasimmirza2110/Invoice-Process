package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
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
 * Phase 6 on the TEST database: the three dashboards are role-scoped and their numbers follow the documented
 * definitions. (The admin dashboard is global, so it is checked with "at least" where other tests add rows.)
 */
class DashboardPgIntegrationTest extends AbstractQueuePgTest {

    @AfterEach
    void leaveTheQueueEmpty() {
        drainQueue();
    }

    @Test
    void dashboardsAreRoleScopedAndFollowTheDefinitions() throws Exception {
        drainQueue();
        AppUser vendor = createUser(Role.VENDOR);
        AppUser reviewer = createUser(Role.REVIEWER);
        String category = "DSH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Vendor uploads two invoices; one extraction has a wrong total, the other passes every check.
        actAs(vendor);
        long mismatch = queueOneInvoice("dash-mismatch");
        long clean = queueOneInvoice("dash-clean");
        TokenUsage usage = new TokenUsage();
        usage.setModelId("gemini-test");
        usage.setPromptTokenCount(700L);
        usage.setCandidatesTokenCount(100L);
        usage.setTotalTokenCount(800L);
        InvoiceExtractionResult wrong = validResult(unique("DASH"));
        wrong.setTotalAmount(new BigDecimal("999.00"));
        extractorReturns(wrong, usage);
        worker.processNextJob();
        extractorReturns(validResult(unique("DASH")), usage);
        worker.processNextJob();

        // Admin routes the mismatch to the reviewer; the reviewer corrects the total and submits V2.
        actAs(testAdmin());
        send(HttpMethod.POST, "/api/admin/categories", "{\"code\":\"" + category + "\",\"displayName\":\"Dashboard test\"}");
        send(HttpMethod.PUT, "/api/admin/users/" + reviewer.getId() + "/categories", "{\"categories\":[\"" + category + "\"]}");
        send(HttpMethod.PUT, "/api/admin/invoices/" + mismatch + "/category", "{\"categoryCode\":\"" + category + "\"}");
        send(HttpMethod.POST, "/api/admin/assignments", "{\"invoiceIds\":[" + mismatch + "],\"reviewerId\":" + reviewer.getId() + "}");
        actAs(reviewer);
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + mismatch, "{\"baseVersion\":1,\"invoiceNumber\":\"" + unique("FIX")
                + "\",\"currency\":\"INR\",\"subtotal\":\"100.00\",\"taxAmount\":\"18.00\",\"totalAmount\":\"118.00\","
                + "\"items\":[{\"description\":\"Item\",\"quantity\":\"1\",\"unitPrice\":\"100.00\",\"taxRate\":\"18\","
                + "\"lineTotal\":\"100.00\"}]}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, "/api/invoices/" + mismatch + "/submit", "{\"version\":2}").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ---- vendor: only their own invoices
        actAs(vendor);
        JsonNode v = json(restTemplate.getForObject("/api/dashboard/vendor?days=1", String.class));
        assertThat(v.get("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(v.at("/statusCounts/NEEDS_REVIEW").asInt()).isEqualTo(1);
        assertThat(v.at("/statusCounts/PENDING_APPROVAL").asInt()).isEqualTo(1);
        assertThat(v.get("receivedToday").asInt()).isEqualTo(2);
        assertThat(v.at("/geminiUsage/calls").asInt()).isEqualTo(2);
        assertThat(v.at("/geminiUsage/reportedTotalTokens").asInt()).isEqualTo(1600);
        assertThat(restTemplate.getForEntity("/api/dashboard/admin", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ---- reviewer: own assignments and own corrections
        actAs(reviewer);
        JsonNode r = json(restTemplate.getForObject("/api/dashboard/reviewer?days=1", String.class));
        assertThat(r.at("/assignedStatusCounts/PENDING_APPROVAL").asInt()).isEqualTo(1);
        assertThat(r.get("submittedInPeriod").asInt()).isEqualTo(1);
        assertThat(r.get("correctionsSavedInPeriod").asInt()).isEqualTo(1);
        // The correction sent a new invoiceNumber and total and left invoiceDate + sellerName empty: 4 changed fields
        // out of 12 header + 5 item fields compared.
        assertThat(r.at("/myCorrectionRate/fieldsChanged").asInt()).isEqualTo(4);
        assertThat(r.at("/myCorrectionRate/changesByField").toString()).contains("totalAmount").contains("invoiceDate");
        assertThat(r.at("/myCorrectionRate/fieldsCompared").asInt()).isEqualTo(17);

        // ---- admin: global, with honest quality metrics
        actAs(testAdmin());
        JsonNode a = json(restTemplate.getForObject("/api/dashboard/admin?days=1", String.class));
        assertThat(a.at("/quality/automaticChecksPassed/extracted").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(a.at("/quality/reviewReasons/counts/TOTAL_MISMATCH").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(a.at("/quality/accuracy/observedFieldMatchRate").isNull()).isTrue();
        assertThat(a.at("/quality/accuracy/definition").asText()).contains("labelled sample");
        assertThat(a.at("/decisionsInPeriod/PENDING_APPROVAL").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(a.at("/queue/quota/configured").asBoolean()).isTrue();
        assertThat(a.get("legacyInvoices").asLong()).isGreaterThanOrEqualTo(0);
        boolean reviewerListed = false;
        for (JsonNode row : a.get("reviewerWorkload")) {
            if (row.get("reviewerId").asLong() == reviewer.getId()) {
                reviewerListed = true;
                assertThat(row.get("PENDING_APPROVAL").asInt()).isEqualTo(1);
            }
        }
        assertThat(reviewerListed).isTrue();
        assertThat(status(clean)).isEqualTo("NEEDS_REVIEW");
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(jsonBody, headers), String.class);
    }
}
