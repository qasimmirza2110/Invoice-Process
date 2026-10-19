package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.queue.ExtractionWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Helpers shared by the PostgreSQL tests of the batch/queue/review/approval workflow (test database only). */
abstract class AbstractQueuePgTest extends AbstractPgIntegrationTest {

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ExtractionWorker worker;

    /**
     * The test database keeps rows from earlier runs. A job still QUEUED from an interrupted run would be "older"
     * than a test's own jobs and be started first, so the queue is emptied (those synthetic rows become FAILED).
     */
    protected void drainQueue() {
        doThrow(new InvoiceExtractionException("drained by test setup")).when(llmInvoiceExtractor).extract(any(byte[].class));
        for (int i = 0; i < 1000 && worker.processNextJob(); i++) {
            // keep draining
        }
        reset(llmInvoiceExtractor);
        clearInvocations(llmInvoiceExtractor);
    }

    protected void extractorReturns(InvoiceExtractionResult result, TokenUsage usage) {
        doReturn(new LlmExtractionResponse(result, usage)).when(llmInvoiceExtractor).extract(any(byte[].class));
    }

    protected void extractorThrows(InvoiceExtractionException e) {
        doThrow(e).when(llmInvoiceExtractor).extract(any(byte[].class));
    }

    protected long createBatch(String label) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.postForEntity("/api/batches",
                new HttpEntity<>("{\"label\":\"" + label + "\"}", headers), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(created.getBody()).get("id").asLong();
    }

    protected ResponseEntity<String> uploadFile(long batchId, byte[] bytes, String fileName, String relativePath) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (relativePath != null) {
            body.add("relativePath", relativePath);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity("/api/batches/" + batchId + "/files", new HttpEntity<>(body, headers), String.class);
    }

    /** Uploads one synthetic PDF into a new batch and returns the new invoice id. */
    protected long queueOneInvoice(String marker) throws Exception {
        long batchId = createBatch(marker);
        ResponseEntity<String> upload = uploadFile(batchId, syntheticPdf(marker), marker + ".pdf", null);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return json(upload.getBody()).get("invoiceId").asLong();
    }

    /** Synthetic bytes only - never a real invoice. */
    protected static byte[] syntheticPdf(String marker) {
        return ("%PDF-1.4\n% synthetic workflow test " + marker + " " + UUID.randomUUID() + "\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** A consistent synthetic invoice: 100.00 + 18.00 tax = 118.00. */
    protected static InvoiceExtractionResult validResult(String number) {
        InvoiceExtractionResult r = new InvoiceExtractionResult();
        r.setInvoiceNumber(number);
        r.setInvoiceDate("2026-10-06");
        r.setSellerName("Synthetic Workflow Seller " + number);
        r.setCurrency("INR");
        r.setSubtotal(new BigDecimal("100.00"));
        r.setTaxAmount(new BigDecimal("18.00"));
        r.setTotalAmount(new BigDecimal("118.00"));
        r.setItems(new ArrayList<>(Collections.singletonList(new InvoiceItemDto("Item", BigDecimal.ONE, new BigDecimal("100.00"),
                new BigDecimal("18"), new BigDecimal("100.00")))));
        r.setRawResponse("{\"synthetic\":true}");
        return r;
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String status(long invoiceId) {
        return jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId);
    }

    protected List<String> events(long invoiceId) {
        return jdbc.queryForList("select coalesce(from_status, 'null') || '->' || to_status || ' by ' "
                        + "|| coalesce('user:' || actor_user_id, 'system') from invoice_workflow_events where invoice_id = ? order by id",
                String.class, invoiceId);
    }

    protected JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
