package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.entity.VersionChangeSource;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * End-to-end against real PostgreSQL (test database only): HTTP -> controllers -> services -> Hibernate -> PostgreSQL,
 * with the LLM mocked. Verifies what Mockito tests cannot: JSONB, constraints, FOR UPDATE, rollback, Jackson.
 */
class InvoiceVersioningPgIntegrationTest extends AbstractPgIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------ helpers

    private InvoiceExtractionResult synthetic(String invoiceNumber) {
        InvoiceExtractionResult r = new InvoiceExtractionResult();
        r.setInvoiceNumber(invoiceNumber);
        r.setInvoiceDate("2026-10-06");
        r.setSellerName("Synthetic Seller Ltd");
        r.setSellerTaxId("SELLER-TAX-1");
        r.setBuyerName("Synthetic Buyer LLP");
        r.setBuyerTaxId("BUYER-TAX-1");
        r.setCurrency("INR");
        r.setSubtotal(new BigDecimal("10500.00"));
        r.setDiscountAmount(new BigDecimal("500.00"));
        r.setShippingAmount(new BigDecimal("100.00"));
        r.setTaxAmount(new BigDecimal("1890.00"));
        r.setTotalAmount(new BigDecimal("11990.00"));
        r.setItems(Arrays.asList(
                new InvoiceItemDto("Laptop Stand", new BigDecimal("2"), new BigDecimal("5000.00"),
                        new BigDecimal("18"), new BigDecimal("10000.00")),
                new InvoiceItemDto("USB-C Cable", new BigDecimal("1"), new BigDecimal("500.00"),
                        new BigDecimal("18"), new BigDecimal("500.00"))));
        r.setRawResponse("{\"synthetic\":true}");
        return r;
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Synthetic bytes only - a minimal "%PDF-" header plus a unique marker. Never a real invoice. */
    private byte[] syntheticPdf(String marker) {
        return ("%PDF-1.4\n% synthetic integration-test document " + marker + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private ResponseEntity<String> upload(byte[] bytes, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/invoices/extract", new HttpEntity<>(body, headers), String.class);
    }

    /** Extracts a synthetic invoice through the real endpoint and returns the response JSON. */
    private JsonNode extract(InvoiceExtractionResult result, byte[] pdf) throws Exception {
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenReturn(new LlmExtractionResponse(result, null));
        ResponseEntity<String> response = upload(pdf, unique("synthetic") + ".pdf");
        assertThat(response.getStatusCodeValue()).as(response.getBody()).isEqualTo(201);
        return json(response.getBody());
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /** The complete edit body, built from the current invoice JSON, as the frontend sends it (amounts as strings). */
    private Map<String, Object> editBody(JsonNode invoice, int baseVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", baseVersion);
        for (String f : new String[]{"invoiceNumber", "invoiceDate", "sellerName", "sellerTaxId", "buyerName",
                "buyerTaxId", "currency"}) {
            body.put(f, invoice.get(f).isNull() ? null : invoice.get(f).asText());
        }
        for (String f : new String[]{"subtotal", "discountAmount", "shippingAmount", "taxAmount", "totalAmount"}) {
            body.put(f, invoice.get(f).isNull() ? null : invoice.get(f).decimalValue().toPlainString());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : invoice.get("items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", item.get("description").asText());
            for (String f : new String[]{"quantity", "unitPrice", "taxRate", "lineTotal"}) {
                m.put(f, item.get(f).isNull() ? null : item.get(f).decimalValue().toPlainString());
            }
            items.add(m);
        }
        body.put("items", items);
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private ResponseEntity<String> put(long invoiceId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/invoices/" + invoiceId, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private int versionCount(long invoiceId) {
        return jdbc.queryForObject("select count(*) from invoice_versions where invoice_id = ?", Integer.class, invoiceId);
    }

    private String snapshotText(long invoiceId, int version) {
        return jdbc.queryForObject("select snapshot_data::text from invoice_versions where invoice_id = ? and version_number = ?",
                String.class, invoiceId, version);
    }

    private Map<String, Object> invoiceRow(long invoiceId) {
        return jdbc.queryForMap("select invoice_number, seller_name, total_amount, updated_at from invoices where id = ?", invoiceId);
    }

    private List<Map<String, Object>> itemRows(long invoiceId) {
        return jdbc.queryForList("select id, description, quantity, unit_price, line_total from invoice_items "
                + "where invoice_id = ? order by id", invoiceId);
    }

    private long storedFileCount() throws Exception {
        if (!Files.exists(STORAGE_DIR)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(STORAGE_DIR)) {
            return files.count();
        }
    }

    // ------------------------------------------------------------------ schema

    @Test
    void schemaHasJsonbColumnsConstraintsAndUnchangedMoneyColumns() {
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_name = 'invoice_versions' "
                + "and column_name = 'snapshot_data'", String.class)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_name = 'invoice_versions' "
                + "and column_name = 'changed_fields'", String.class)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_name = 'invoices' "
                + "and column_name = 'source_document_id'", String.class)).isEqualTo("YES");

        Map<String, Object> constraints = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select conname, contype::text as contype from pg_constraint where conname in "
                + "('uk_invoice_versions_invoice_version', 'fk_invoice_versions_invoice', 'fk_invoices_source_document', "
                + "'fk_invoice_versions_source_document')")) {
            constraints.put((String) row.get("conname"), row.get("contype"));
        }
        assertThat(constraints).containsEntry("uk_invoice_versions_invoice_version", "u")
                .containsEntry("fk_invoice_versions_invoice", "f")
                .containsEntry("fk_invoices_source_document", "f")
                .containsEntry("fk_invoice_versions_source_document", "f");

        // The unique constraint's index is on (invoice_id, version_number) - it serves the history lookup.
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'uk_invoice_versions_invoice_version'",
                String.class)).contains("(invoice_id, version_number)");

        // Existing money precision is untouched.
        Map<String, Object> total = jdbc.queryForMap("select numeric_precision, numeric_scale from information_schema.columns "
                + "where table_name = 'invoices' and column_name = 'total_amount'");
        assertThat(((Number) total.get("numeric_precision")).intValue()).isEqualTo(19);
        assertThat(((Number) total.get("numeric_scale")).intValue()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ extraction -> V1

    @Test
    void extractionStoresPdfInvoiceItemsAndJsonbV1InOneGo() throws Exception {
        byte[] pdf = syntheticPdf(unique("v1"));
        JsonNode inv = extract(synthetic(unique("INV")), pdf);
        long id = inv.get("id").asLong();

        assertThat(inv.get("status").asText()).isEqualTo("EXTRACTED");
        assertThat(inv.get("currentVersion").asInt()).isEqualTo(1);
        String sha = InvoiceDocumentStorage.sha256Hex(pdf);
        assertThat(inv.at("/sourceDocument/sha256").asText()).isEqualTo(sha);
        String docId = inv.at("/sourceDocument/id").asText();

        // The exact bytes are stored privately under the generated id.
        Path stored = STORAGE_DIR.resolve(docId + ".pdf");
        assertThat(Files.readAllBytes(stored)).isEqualTo(pdf);
        assertThat(jdbc.queryForObject("select sha256 from invoice_documents where id = ?", String.class, docId)).isEqualTo(sha);
        assertThat(jdbc.queryForObject("select source_document_id from invoices where id = ?", String.class, id)).isEqualTo(docId);

        Map<String, Object> v1 = jdbc.queryForMap("select change_source, jsonb_typeof(snapshot_data) as kind, "
                + "jsonb_array_length(snapshot_data->'items') as item_count, snapshot_data->>'totalAmount' as total, "
                + "changed_fields is null as no_changes, source_document_id, changed_by "
                + "from invoice_versions where invoice_id = ? and version_number = 1", id);
        assertThat(v1.get("change_source")).isEqualTo("LLM_EXTRACTION");
        assertThat(v1.get("kind")).isEqualTo("object");
        assertThat(((Number) v1.get("item_count")).intValue()).isEqualTo(itemRows(id).size()).isEqualTo(2);
        assertThat(v1.get("total")).isEqualTo("11990.00");
        assertThat(v1.get("no_changes")).isEqualTo(true);
        assertThat(v1.get("source_document_id")).isEqualTo(docId);
        // Phase 1: the version records who caused it (the logged-in test admin who uploaded).
        assertThat(v1.get("changed_by")).isEqualTo("user:" + testAdmin().getId());

        // GET returns the same current data and version.
        JsonNode got = json(http.getForObject("/api/invoices/" + id, String.class));
        assertThat(got.get("currentVersion").asInt()).isEqualTo(1);
        assertThat(got.at("/sourceDocument/id").asText()).isEqualTo(docId);
    }

    @Test
    void documentEndpointServesTheExactBytesAndRejectsUnknownOrMalformedIds() throws Exception {
        byte[] pdf = syntheticPdf(unique("doc"));
        JsonNode inv = extract(synthetic(unique("INV")), pdf);
        String docId = inv.at("/sourceDocument/id").asText();

        ResponseEntity<byte[]> ok = http.getForEntity("/api/documents/" + docId, byte[].class);
        assertThat(ok.getStatusCodeValue()).isEqualTo(200);
        assertThat(ok.getBody()).isEqualTo(pdf);
        assertThat(ok.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(ok.getHeaders().getCacheControl()).contains("no-store");

        for (String bad : new String[]{UUID.randomUUID().toString(), "not-a-uuid", docId.toUpperCase(), "..", "..%5C..%5Cwindows"}) {
            ResponseEntity<String> r = http.getForEntity("/api/documents/" + bad, String.class);
            assertThat(r.getStatusCodeValue()).as(bad).isIn(400, 404);
            assertThat(r.getBody()).as(bad).doesNotContain(STORAGE_DIR.toString()).doesNotContain("pgtest-documents");
        }
    }

    @Test
    void tamperedStoredFileIsDetectedByTheHashCheck() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("tamper")));
        String docId = inv.at("/sourceDocument/id").asText();
        Files.write(STORAGE_DIR.resolve(docId + ".pdf"), "%PDF-1.4 changed on disk".getBytes(StandardCharsets.US_ASCII));

        ResponseEntity<String> r = http.getForEntity("/api/documents/" + docId, String.class);
        assertThat(r.getStatusCodeValue()).isEqualTo(500);
        assertThat(r.getBody()).contains("integrity").doesNotContain(STORAGE_DIR.toString());
    }

    @Test
    void failedVersionWriteDuringExtractionRollsBackEverythingAndDeletesTheFile() throws Exception {
        doThrow(new IllegalStateException("simulated version insert failure"))
                .when(versionWriter).append(any(), eq(1), eq(VersionChangeSource.LLM_EXTRACTION), any(), any(), any());
        when(llmInvoiceExtractor.extract(any(byte[].class)))
                .thenReturn(new LlmExtractionResponse(synthetic(unique("INV")), null));
        String fileName = unique("rollback") + ".pdf";
        long filesBefore = storedFileCount();
        long documentsBefore = jdbc.queryForObject("select count(*) from invoice_documents", Long.class);

        ResponseEntity<String> r = upload(syntheticPdf(fileName), fileName);

        assertThat(r.getStatusCodeValue()).isEqualTo(500);
        Map<String, Object> row = jdbc.queryForMap("select id, status, failure_reason, source_document_id from invoices "
                + "where source_file_name = ?", fileName);
        long id = ((Number) row.get("id")).longValue();
        // The rolled-back transaction committed nothing. Afterwards the pre-existing markFailed() records the
        // traceable FAILED row (it keeps the header values that were already on the in-memory object, as it
        // always has for a failed save - status FAILED is what marks them as unusable).
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("Unexpected error during processing");
        assertThat(row.get("source_document_id")).isNull();
        assertThat(itemRows(id)).isEmpty();
        assertThat(versionCount(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from invoice_documents", Long.class)).isEqualTo(documentsBefore);
        assertThat(storedFileCount()).isEqualTo(filesBefore);          // the stored PDF was deleted again
    }

    @Test
    void existingExtractionValidationFailureStillReturnsTheOld422Shape() throws Exception {
        InvoiceExtractionResult wrong = synthetic(unique("INV"));
        wrong.setTotalAmount(new BigDecimal("99999.00"));
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenReturn(new LlmExtractionResponse(wrong, null));
        long filesBefore = storedFileCount();

        ResponseEntity<String> r = upload(syntheticPdf(unique("bad")), unique("bad") + ".pdf");

        assertThat(r.getStatusCodeValue()).isEqualTo(422);
        JsonNode body = json(r.getBody());
        assertThat(body.has("fieldErrors")).isFalse();
        assertThat(body.get("error").asText()).isEqualTo("Invoice validation failed");
        assertThat(storedFileCount()).isEqualTo(filesBefore);          // nothing is stored for a rejected extraction
    }

    // ------------------------------------------------------------------ corrections

    @Test
    void firstCorrectionCreatesV2_secondCreatesV3_andEarlierSnapshotsNeverChange() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("lifecycle")));
        long id = inv.get("id").asLong();
        String docId = inv.at("/sourceDocument/id").asText();
        String v1Before = snapshotText(id, 1);
        List<Map<String, Object>> itemsBefore = itemRows(id);

        // V2: fix the invoice number (leading zeros!), seller and one line description.
        Map<String, Object> edit = editBody(inv, 1);
        edit.put("invoiceNumber", "000000005");
        edit.put("sellerTaxId", "6000000001");
        edit.put("sellerName", "Corrected Seller Ltd");
        items(edit).get(0).put("description", "Laptop Stand (aluminium)");
        edit.put("changeReason", "OCR misread the invoice number");
        ResponseEntity<String> r2 = put(id, edit);
        assertThat(r2.getStatusCodeValue()).as(r2.getBody()).isEqualTo(200);
        JsonNode saved2 = json(r2.getBody());
        assertThat(saved2.get("changed").asBoolean()).isTrue();
        assertThat(saved2.at("/invoice/currentVersion").asInt()).isEqualTo(2);
        assertThat(saved2.at("/invoice/invoiceNumber").asText()).isEqualTo("000000005");

        assertThat(snapshotText(id, 1)).isEqualTo(v1Before);                         // V1 untouched
        Map<String, Object> v2 = jdbc.queryForMap("select change_source, change_reason, source_document_id, "
                + "snapshot_data->>'invoiceNumber' as num, snapshot_data->>'sellerTaxId' as tax, "
                + "jsonb_array_length(changed_fields) as changes from invoice_versions where invoice_id = ? and version_number = 2", id);
        assertThat(v2.get("change_source")).isEqualTo("MANUAL_EDIT");
        assertThat(v2.get("change_reason")).isEqualTo("OCR misread the invoice number");
        assertThat(v2.get("source_document_id")).isEqualTo(docId);                   // same original PDF
        assertThat(v2.get("num")).isEqualTo("000000005");
        assertThat(v2.get("tax")).isEqualTo("6000000001");
        assertThat(((Number) v2.get("changes")).intValue()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select invoice_number from invoices where id = ?", String.class, id)).isEqualTo("000000005");
        // Items were updated in place (same row ids, same order).
        List<Map<String, Object>> itemsAfter = itemRows(id);
        assertThat(itemsAfter).extracting(m -> m.get("id")).containsExactlyElementsOf(
                itemsBefore.stream().map(m -> m.get("id")).collect(Collectors.toList()));
        assertThat(itemsAfter.get(0).get("description")).isEqualTo("Laptop Stand (aluminium)");

        // V3: add a line item and fix the totals accordingly.
        JsonNode current = saved2.get("invoice");
        String v2Before = snapshotText(id, 2);
        Map<String, Object> edit3 = editBody(current, 2);
        Map<String, Object> newItem = new LinkedHashMap<>();
        newItem.put("description", "HDMI Adapter");
        newItem.put("quantity", "3");
        newItem.put("unitPrice", "100.00");
        newItem.put("taxRate", "18");
        newItem.put("lineTotal", "300.00");
        items(edit3).add(newItem);
        edit3.put("subtotal", "10800.00");
        edit3.put("taxAmount", "1944.00");
        edit3.put("totalAmount", "12344.00");
        ResponseEntity<String> r3 = put(id, edit3);
        assertThat(r3.getStatusCodeValue()).as(r3.getBody()).isEqualTo(200);
        assertThat(json(r3.getBody()).at("/invoice/currentVersion").asInt()).isEqualTo(3);
        assertThat(snapshotText(id, 1)).isEqualTo(v1Before);
        assertThat(snapshotText(id, 2)).isEqualTo(v2Before);
        assertThat(itemRows(id)).hasSize(3);
        assertThat(jdbc.queryForObject("select jsonb_array_length(snapshot_data->'items') from invoice_versions "
                + "where invoice_id = ? and version_number = 3", Integer.class, id)).isEqualTo(3);

        // History API: newest first; version 1 still shows the original extraction.
        JsonNode list = json(http.getForObject("/api/invoices/" + id + "/versions", String.class));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("versionNumber").asInt()).isEqualTo(3);
        assertThat(list.get(2).get("changeSource").asText()).isEqualTo("LLM_EXTRACTION");
        assertThat(list.get(0).has("snapshot")).isFalse();
        JsonNode version1 = json(http.getForObject("/api/invoices/" + id + "/versions/1", String.class));
        assertThat(version1.at("/snapshot/invoiceNumber").asText()).isEqualTo(inv.get("invoiceNumber").asText());
        assertThat(version1.at("/snapshot/items")).hasSize(2);
        JsonNode compare = json(http.getForObject("/api/invoices/" + id + "/versions/compare?from=1&to=3", String.class));
        Set<String> changed = new HashSet<>();
        compare.get("differences").forEach(d -> changed.add(d.get("field").asText()));
        assertThat(changed).contains("invoiceNumber", "sellerName", "items[0].description", "items[2]", "totalAmount");

        // The current invoice equals the latest snapshot.
        JsonNode got = json(http.getForObject("/api/invoices/" + id, String.class));
        JsonNode v3 = json(http.getForObject("/api/invoices/" + id + "/versions/3", String.class)).get("snapshot");
        assertThat(got.get("invoiceNumber").asText()).isEqualTo(v3.get("invoiceNumber").asText());
        assertThat(got.get("totalAmount").decimalValue()).isEqualByComparingTo(v3.get("totalAmount").asText());
        assertThat(got.get("items")).hasSize(v3.get("items").size());
        assertThat(got.get("currentVersion").asInt()).isEqualTo(3);
    }

    @Test
    void unchangedSaveCreatesNoVersion() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("noop")));
        long id = inv.get("id").asLong();
        Map<String, Object> same = editBody(inv, 1);
        // Same values, different spelling: "10500" vs "10500.00", padded text.
        same.put("subtotal", "10500");
        same.put("sellerName", "  Synthetic Seller Ltd  ");

        ResponseEntity<String> r = put(id, same);

        assertThat(r.getStatusCodeValue()).isEqualTo(200);
        assertThat(json(r.getBody()).get("changed").asBoolean()).isFalse();
        assertThat(json(r.getBody()).at("/invoice/currentVersion").asInt()).isEqualTo(1);
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void staleBaseVersionIsRejectedWith409AndChangesNothing() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("stale")));
        long id = inv.get("id").asLong();
        Map<String, Object> first = editBody(inv, 1);
        first.put("buyerName", "First editor");
        assertThat(put(id, first).getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> rowAfterFirst = invoiceRow(id);
        String v2 = snapshotText(id, 2);

        Map<String, Object> stale = editBody(inv, 1);     // still based on version 1
        stale.put("buyerName", "Second editor");
        ResponseEntity<String> r = put(id, stale);

        assertThat(r.getStatusCodeValue()).isEqualTo(409);
        JsonNode err = json(r.getBody());
        assertThat(err.get("error").asText()).isEqualTo("Version conflict");
        assertThat(err.get("message").asText()).contains("Reload the latest version");
        assertThat(versionCount(id)).isEqualTo(2);
        assertThat(invoiceRow(id)).isEqualTo(rowAfterFirst);
        assertThat(snapshotText(id, 2)).isEqualTo(v2);
    }

    @Test
    void rowLockMakesAConcurrentSaveWaitUntilTheLockIsReleased() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("lock")));
        long id = inv.get("id").asLong();
        Map<String, Object> edit = editBody(inv, 1);
        edit.put("buyerName", "Waited for the lock");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Thread A: hold SELECT ... FOR UPDATE on the invoice row.
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                invoiceRepository.findByIdForUpdate(id).orElseThrow(IllegalStateException::new);
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // Thread B: a normal save must block on the lock.
            Future<ResponseEntity<String>> save = pool.submit(() -> put(id, edit));
            Thread.sleep(1500);
            assertThat(save.isDone()).as("save must wait for the row lock").isFalse();
            // PostgreSQL's own view: the save's invoice read is waiting on a lock held by another session.
            // (pg_stat_activity.query is cut at track_activity_query_size, so Hibernate's trailing
            // "for update" is not visible there; pg_blocking_pids proves the blocking instead.)
            List<Map<String, Object>> waiting = jdbc.queryForList("select wait_event, left(query, 40) as q, "
                    + "cardinality(pg_blocking_pids(pid)) as blockers from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock'");
            assertThat(waiting).hasSize(1);
            assertThat(((Number) waiting.get(0).get("blockers")).intValue()).isGreaterThanOrEqualTo(1);
            assertThat((String) waiting.get(0).get("q")).startsWith("select invoice0_.id");

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(save.get(20, TimeUnit.SECONDS).getStatusCodeValue()).isEqualTo(200);
            assertThat(versionCount(id)).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void twoSimultaneousSavesFromTheSameVersion_oneWinsOneGets409() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("race")));
        long id = inv.get("id").asLong();
        Map<String, Object> a = editBody(inv, 1);
        a.put("buyerName", "Editor A");
        Map<String, Object> b = editBody(inv, 1);
        b.put("buyerName", "Editor B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (Map<String, Object> body : Arrays.asList(a, b)) {
                calls.add(() -> {
                    start.await();
                    return put(id, body).getStatusCodeValue();
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("select version_number from invoice_versions where invoice_id = ? order by version_number",
                Integer.class, id)).containsExactly(1, 2);
    }

    @Test
    void databaseRejectsADuplicateVersionNumber() throws Exception {
        long id = extract(synthetic(unique("INV")), syntheticPdf(unique("dup"))).get("id").asLong();
        assertThatThrownBy(() -> jdbc.update("insert into invoice_versions (invoice_id, version_number, change_source, "
                + "snapshot_data, created_at) values (?, 1, 'MANUAL_EDIT', '{}'::jsonb, now())", id))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_invoice_versions_invoice_version");
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void failedVersionInsertRollsBackTheInvoiceAndItemChanges() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("edit-rollback")));
        long id = inv.get("id").asLong();
        Map<String, Object> rowBefore = invoiceRow(id);
        List<Map<String, Object>> itemsBefore = itemRows(id);
        doThrow(new IllegalStateException("simulated version insert failure"))
                .when(versionWriter).append(any(), eq(2), eq(VersionChangeSource.MANUAL_EDIT), any(), any(), any());

        Map<String, Object> edit = editBody(inv, 1);
        edit.put("sellerName", "Must not be saved");
        items(edit).remove(1);
        edit.put("subtotal", "10000.00");
        edit.put("taxAmount", "1800.00");
        edit.put("totalAmount", "11400.00");
        ResponseEntity<String> r = put(id, edit);

        assertThat(r.getStatusCodeValue()).isEqualTo(500);
        assertThat(r.getBody()).doesNotContain("simulated");
        assertThat(invoiceRow(id)).isEqualTo(rowBefore);
        assertThat(itemRows(id)).isEqualTo(itemsBefore);
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void legacyInvoiceGetsAnHonestBaselineThenTheCorrection() throws Exception {
        // An invoice row as it existed before versioning: no versions, no stored PDF.
        String number = unique("LEGACY");
        Long id = jdbc.queryForObject("insert into invoices (invoice_number, invoice_date, seller_name, currency, subtotal, "
                + "tax_amount, total_amount, source_file_name, source_file_size, status, created_at, updated_at) "
                + "values (?, date '2026-01-15', 'Legacy Seller', 'USD', 100.00, 10.00, 110.00, 'legacy.pdf', 1234, "
                + "'EXTRACTED', now(), now()) returning id", Long.class, number);
        jdbc.update("insert into invoice_items (invoice_id, description, quantity, unit_price, tax_rate, line_total) "
                + "values (?, 'Legacy item', 1.000, 100.00, 10.000, 100.00)", id);

        JsonNode before = json(http.getForObject("/api/invoices/" + id, String.class));
        assertThat(before.get("currentVersion").asInt()).isZero();
        assertThat(before.get("sourceDocument").isNull()).isTrue();
        assertThat(json(http.getForObject("/api/invoices/" + id + "/versions", String.class))).isEmpty();

        Map<String, Object> stale = editBody(before, 1);
        assertThat(put(id, stale).getStatusCodeValue()).isEqualTo(409);

        Map<String, Object> edit = editBody(before, 0);
        edit.put("sellerName", "Legacy Seller (corrected)");
        ResponseEntity<String> r = put(id, edit);
        assertThat(r.getStatusCodeValue()).as(r.getBody()).isEqualTo(200);
        assertThat(json(r.getBody()).at("/invoice/currentVersion").asInt()).isEqualTo(2);

        List<Map<String, Object>> versions = jdbc.queryForList("select version_number, change_source, change_reason, "
                + "source_document_id, snapshot_data->>'sellerName' as seller from invoice_versions where invoice_id = ? "
                + "order by version_number", id);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("change_source")).isEqualTo("LEGACY_BASELINE");
        assertThat((String) versions.get(0).get("change_reason")).contains("NOT a verified original LLM extraction");
        assertThat(versions.get(0).get("seller")).isEqualTo("Legacy Seller");
        assertThat(versions.get(0).get("source_document_id")).isNull();
        assertThat(versions.get(1).get("change_source")).isEqualTo("MANUAL_EDIT");
        assertThat(versions.get(1).get("seller")).isEqualTo("Legacy Seller (corrected)");
    }

    // ------------------------------------------------------------------ request validation through the real Jackson setup

    @Test
    void unknownAndPrivilegedFieldsAreRejectedNotIgnored() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("unknown")));
        long id = inv.get("id").asLong();
        Map<String, Object> rowBefore = invoiceRow(id);
        Map<String, Object> edit = editBody(inv, 1);
        edit.put("sellerName", "Should not be saved");
        edit.put("id", 999999);
        edit.put("status", "FAILED");
        edit.put("versionNumber", 77);
        items(edit).get(0).put("invoiceId", 1);

        ResponseEntity<String> r = put(id, edit);

        assertThat(r.getStatusCodeValue()).isEqualTo(400);
        Set<String> fields = new HashSet<>();
        json(r.getBody()).get("fieldErrors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("id", "status", "versionNumber", "items[0].invoiceId");
        assertThat(invoiceRow(id)).isEqualTo(rowBefore);
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void businessRuleAndDecimalViolationsReturn422WithFieldErrors() throws Exception {
        JsonNode inv = extract(synthetic(unique("INV")), syntheticPdf(unique("invalid")));
        long id = inv.get("id").asLong();
        Map<String, Object> edit = editBody(inv, 1);
        edit.put("totalAmount", "12000.00");          // breaks subtotal - discount + shipping + tax = total
        items(edit).get(1).put("unitPrice", "500.555"); // 3 decimals would be silently rounded by numeric(19,2)
        edit.put("invoiceDate", "06/10/2026");

        ResponseEntity<String> r = put(id, edit);

        assertThat(r.getStatusCodeValue()).isEqualTo(422);
        Set<String> fields = new HashSet<>();
        json(r.getBody()).get("fieldErrors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("totalAmount", "items[1].unitPrice", "invoiceDate");
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void failedUploadsCannotBeEdited() throws Exception {
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenThrow(new InvoiceExtractionException("simulated provider failure"));
        String fileName = unique("failed") + ".pdf";
        assertThat(upload(syntheticPdf(fileName), fileName).getStatusCodeValue()).isEqualTo(502);
        long id = jdbc.queryForObject("select id from invoices where source_file_name = ?", Long.class, fileName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", 0);
        body.put("invoiceNumber", "X-1");
        body.put("totalAmount", "1.00");
        body.put("items", new ArrayList<>());
        ResponseEntity<String> r = put(id, body);

        assertThat(r.getStatusCodeValue()).isEqualTo(409);
        assertThat(json(r.getBody()).get("error").asText()).isEqualTo("Invoice not editable");
        assertThat(versionCount(id)).isZero();
    }

    @Test
    void unknownInvoiceAndVersionReturn404() throws Exception {
        long id = extract(synthetic(unique("INV")), syntheticPdf(unique("404"))).get("id").asLong();
        assertThat(http.getForEntity("/api/invoices/" + id + "/versions/9", String.class).getStatusCodeValue()).isEqualTo(404);
        assertThat(http.getForEntity("/api/invoices/999999999/versions", String.class).getStatusCodeValue()).isEqualTo(404);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", 1);
        body.put("items", new ArrayList<>());
        assertThat(put(999999999L, body).getStatusCodeValue()).isEqualTo(404);
    }
}
