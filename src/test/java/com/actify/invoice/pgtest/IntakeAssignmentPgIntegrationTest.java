package com.actify.invoice.pgtest;

import com.actify.invoice.storage.InvoiceDocumentStorage;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin intake board and the assignment lifecycle against the TEST database (LLM mocked, worker called directly):
 * upload -> visible on the board at once -> assigned while still QUEUED -> leaves "needs assignment" -> reviewer sees it
 * read-only -> extraction -> editable for that reviewer; original PDF byte-identical throughout; duplicate matrix.
 */
class IntakeAssignmentPgIntegrationTest extends AbstractQueuePgTest {

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
        category = "PGI_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        actAs(testAdmin());
        assertThat(send(HttpMethod.POST, "/api/admin/categories", "{\"code\":\"" + category + "\",\"displayName\":\"PG intake\"}")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (AppUser r : new AppUser[]{reviewer, otherReviewer}) {
            assertThat(send(HttpMethod.PUT, "/api/admin/users/" + r.getId() + "/categories",
                    "{\"categories\":[\"" + category + "\"]}").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @AfterEach
    void leaveTheQueueEmpty() {
        drainQueue();
    }

    @Test
    void aQueuedUploadIsOnTheBoardAtOnceAndCanBeAssignedBeforeExtraction() throws Exception {
        actAs(vendor);
        byte[] pdf = syntheticPdf(unique("intake"));
        long batchId = createBatchWithCategory("intake");
        long id = json(uploadFile(batchId, pdf, "furniture.pdf", null).getBody()).get("invoiceId").asLong();

        // 1. visible to the admin immediately, as waiting for extraction, with an honest reason
        actAs(testAdmin());
        JsonNode board = board();
        JsonNode row = find(board.get("needsAssignment"), id);
        assertThat(row).isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("QUEUED");
        assertThat(row.get("stage").asText()).isEqualTo("WAITING_FOR_EXTRACTION");
        assertThat(row.get("blockedReason").asText()).startsWith("Waiting for extraction");
        assertThat(row.get("categoryCode").asText()).isEqualTo(category);
        assertThat(find(board.get("assigned"), id)).isNull();

        // 2. assign while QUEUED: the row leaves "needs assignment" and appears exactly once under "assigned"
        JsonNode result = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + reviewer.getId() + "}").getBody()).get(0);
        assertThat(result.get("assigned").asBoolean()).isTrue();
        assertThat(result.get("status").asText()).isEqualTo("QUEUED");
        assertThat(result.get("reviewerId").asLong()).isEqualTo(reviewer.getId());
        board = board();
        assertThat(find(board.get("needsAssignment"), id)).isNull();
        assertThat(count(board.get("assigned"), id)).isEqualTo(1);
        assertThat(events(id)).containsExactly("null->QUEUED by user:" + vendor.getId(),
                "QUEUED->QUEUED by user:" + testAdmin().getId());

        // 3. a second plain "Assign" to someone else does NOT silently reassign
        JsonNode again = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + otherReviewer.getId() + "}").getBody()).get(0);
        assertThat(again.get("assigned").asBoolean()).isFalse();
        assertThat(again.get("reviewerId").asLong()).isEqualTo(reviewer.getId());

        // 4. the reviewer sees it but cannot edit it yet (nothing extracted)
        actAs(reviewer);
        assertThat(restTemplate.getForEntity("/api/invoices/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(0, "118.00")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 5. extraction (mocked) keeps the assignment and makes it editable for that reviewer
        extractorReturns(validResult(unique("INT")), null);
        assertThat(worker.processNextJob()).isTrue();
        assertThat(status(id)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select assigned_reviewer_id from invoices where id = ?", Long.class, id))
                .isEqualTo(reviewer.getId());
        assertThat(send(HttpMethod.PUT, "/api/invoices/" + id, correction(1, "118.00")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 6. the original PDF is still byte-identical (V2 never touches it) and served by invoice id
        ResponseEntity<byte[]> served = restTemplate.getForEntity("/api/invoices/" + id + "/document", byte[].class);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(served.getBody()).isEqualTo(pdf);
        assertThat(InvoiceDocumentStorage.sha256Hex(served.getBody())).isEqualTo(jdbc.queryForObject(
                "select d.sha256 from invoices i join invoice_documents d on d.id = i.source_document_id where i.id = ?",
                String.class, id));

        // 7. another reviewer and another vendor get 404 for the invoice and its PDF
        actAs(otherReviewer);
        assertThat(restTemplate.getForEntity("/api/invoices/" + id + "/document", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        actAs(createUser(Role.VENDOR));
        assertThat(restTemplate.getForEntity("/api/invoices/" + id + "/document", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reassignIsExplicitAndAudited() throws Exception {
        actAs(vendor);
        long id = json(uploadFile(createBatchWithCategory("reassign"), syntheticPdf(unique("re")), "re.pdf", null)
                .getBody()).get("invoiceId").asLong();
        actAs(testAdmin());
        send(HttpMethod.POST, "/api/admin/assignments", "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + reviewer.getId() + "}");

        JsonNode moved = json(send(HttpMethod.POST, "/api/admin/assignments",
                "{\"invoiceIds\":[" + id + "],\"reviewerId\":" + otherReviewer.getId() + ",\"reassign\":true}").getBody()).get(0);
        assertThat(moved.get("assigned").asBoolean()).isTrue();
        assertThat(moved.get("message").asText()).isEqualTo("Reassigned.");
        assertThat(jdbc.queryForObject("select reason from invoice_workflow_events where invoice_id = ? order by id desc limit 1",
                String.class, id)).startsWith("Reassigned from ");
        assertThat(count(board().get("assigned"), id)).isEqualTo(1);
    }

    @Test
    void sameVendorDuplicatesAreRejectedAcrossBatchesWhateverTheFileName() throws Exception {
        actAs(vendor);
        byte[] pdf = syntheticPdf(unique("dup-matrix"));
        assertThat(uploadFile(createBatch("first"), pdf, "a.pdf", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> sameName = uploadFile(createBatch("second"), pdf, "a.pdf", null);
        ResponseEntity<String> otherName = uploadFile(createBatch("third"), pdf, "renamed.pdf", null);
        assertThat(sameName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(otherName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(otherName.getBody()).get("code").asText()).isEqualTo("DUPLICATE_FILE");
        assertThat(json(otherName.getBody()).get("message").asText()).isEqualTo("You already uploaded this exact file.");

        // Same name, different bytes: not a duplicate.
        assertThat(uploadFile(createBatch("fourth"), syntheticPdf(unique("other-bytes")), "a.pdf", null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    // ------------------------------------------------------------------ helpers

    private long createBatchWithCategory(String label) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = restTemplate.exchange("/api/batches", HttpMethod.POST, new HttpEntity<>(
                "{\"label\":\"" + label + "\",\"defaultCategoryCode\":\"" + category + "\"}", headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(r.getBody()).get("id").asLong();
    }

    private JsonNode board() throws Exception {
        ResponseEntity<String> r = restTemplate.getForEntity("/api/admin/intake", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(r.getBody());
    }

    private static JsonNode find(JsonNode rows, long id) {
        for (JsonNode r : rows) {
            if (r.get("invoiceId").asLong() == id) {
                return r;
            }
        }
        return null;
    }

    private static int count(JsonNode rows, long id) {
        List<JsonNode> hits = new ArrayList<>();
        rows.forEach(r -> { if (r.get("invoiceId").asLong() == id) hits.add(r); });
        return hits.size();
    }

    private static String correction(int baseVersion, String total) {
        return "{\"baseVersion\":" + baseVersion + ",\"invoiceNumber\":\"" + unique("FIXED") + "\",\"currency\":\"INR\","
                + "\"subtotal\":\"100.00\",\"taxAmount\":\"18.00\",\"totalAmount\":\"" + total + "\",\"changeReason\":\"corrected\","
                + "\"items\":[{\"description\":\"Item\",\"quantity\":\"1\",\"unitPrice\":\"100.00\",\"taxRate\":\"18\",\"lineTotal\":\"100.00\"}]}";
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(jsonBody, headers), String.class);
    }
}
