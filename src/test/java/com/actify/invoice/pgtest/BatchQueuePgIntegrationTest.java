package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.queue.ExtractionJobService;
import com.actify.invoice.queue.ExtractionWorker;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 2 against the TEST database: batch intake, ownership, the PostgreSQL queue (FIFO, SKIP LOCKED,
 * stale-claim recovery) and routing every extracted invoice to NEEDS_REVIEW with reason codes.
 * The LLM is a Mockito mock; the worker is called directly (it is disabled as a background job).
 */
class BatchQueuePgIntegrationTest extends AbstractQueuePgTest {

    @Autowired private ExtractionJobService jobService;
    @Autowired private ExtractionJobRepository jobRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private AppUser vendor;

    /**
     * The test database keeps rows from earlier runs. Any job still QUEUED from an earlier (interrupted) run would
     * be "older" than ours and be started first, so the queue is emptied first (those synthetic rows become FAILED).
     */
    @BeforeEach
    void drainQueueAndLoginAsVendor() {
        drainQueue();
        vendor = createUser(Role.VENDOR);
        actAs(vendor);
    }

    /** Leave no QUEUED job behind for the next test class (FIFO would start it first). */
    @AfterEach
    void leaveTheQueueEmpty() {
        drainQueue();
    }

    // ------------------------------------------------------------------ intake

    @Test
    void acceptedFilesAreStoredAndQueuedWithoutCallingTheLlm() throws Exception {
        long batchId = createBatch("October");
        byte[] pdf = syntheticPdf("accepted");

        ResponseEntity<String> upload = uploadFile(batchId, pdf, "accepted.pdf", null);

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long invoiceId = json(upload.getBody()).get("invoiceId").asLong();
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select owner_user_id from invoices where id = ?", Long.class, invoiceId)).isEqualTo(vendor.getId());
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, invoiceId)).isZero();
        String docId = jdbc.queryForObject("select source_document_id from invoices where id = ?", String.class, invoiceId);
        assertThat(Files.readAllBytes(STORAGE_DIR.resolve(docId + ".pdf"))).isEqualTo(pdf);
        assertThat(events(invoiceId)).containsExactly("null->QUEUED by user:" + vendor.getId());
        org.mockito.Mockito.verifyNoInteractions(llmInvoiceExtractor);

        JsonNode detail = json(restTemplate.getForObject("/api/batches/" + batchId, String.class));
        assertThat(detail.at("/batch/acceptedCount").asInt()).isEqualTo(1);
        assertThat(detail.at("/statusCounts/QUEUED").asInt()).isEqualTo(1);
        assertThat(detail.at("/invoices/0/queuePosition").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void invalidNestedAndDuplicateFilesAreRejectedAndRecordedOnTheBatch() throws Exception {
        long batchId = createBatch("rejections");
        byte[] pdf = syntheticPdf("dup");
        assertThat(uploadFile(batchId, pdf, "first.pdf", "Folder/first.pdf").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> dup = uploadFile(batchId, pdf, "again.pdf", null);
        ResponseEntity<String> nested = uploadFile(batchId, syntheticPdf("nested"), "deep.pdf", "Folder/Sub/deep.pdf");
        ResponseEntity<String> notPdf = uploadFile(batchId, "hello".getBytes(StandardCharsets.US_ASCII), "fake.pdf", null);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(dup.getBody()).get("code").asText()).isEqualTo("DUPLICATE_FILE");
        assertThat(json(nested.getBody()).get("code").asText()).isEqualTo("NESTED_FOLDER");
        assertThat(json(notPdf.getBody()).get("code").asText()).isEqualTo("INVALID_FILE");

        JsonNode detail = json(restTemplate.getForObject("/api/batches/" + batchId, String.class));
        assertThat(detail.at("/batch/acceptedCount").asInt()).isEqualTo(1);
        assertThat(detail.at("/batch/rejectedCount").asInt()).isEqualTo(3);
        List<String> codes = new ArrayList<>();
        detail.get("rejections").forEach(r -> codes.add(r.get("code").asText()));
        assertThat(codes).containsExactly("DUPLICATE_FILE", "NESTED_FOLDER", "INVALID_FILE");
    }

    @Test
    void closedAndFullBatchesRefuseMoreFiles() throws Exception {
        long closed = createBatch("closed");
        assertThat(restTemplate.postForEntity("/api/batches/" + closed + "/close", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(uploadFile(closed, syntheticPdf("late"), "late.pdf", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        long full = createBatch("full");
        jdbc.update("update upload_batches set accepted_count = 100 where id = ?", full); // our own synthetic batch
        assertThat(uploadFile(full, syntheticPdf("101st"), "101.pdf", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void otherVendorsCannotSeeTheBatchItsInvoicesOrPdfsAndReviewersCannotUpload() throws Exception {
        long batchId = createBatch("private");
        long invoiceId = json(uploadFile(batchId, syntheticPdf("private"), "private.pdf", null).getBody()).get("invoiceId").asLong();
        String docId = jdbc.queryForObject("select source_document_id from invoices where id = ?", String.class, invoiceId);
        assertThat(restTemplate.getForEntity("/api/invoices/" + invoiceId, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        actAs(createUser(Role.VENDOR));
        assertThat(restTemplate.getForEntity("/api/batches/" + batchId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/invoices/" + invoiceId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/documents/" + docId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(uploadFile(batchId, syntheticPdf("intruder"), "x.pdf", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        actAs(createUser(Role.REVIEWER));
        assertThat(restTemplate.getForEntity("/api/batches", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        actAs(testAdmin());
        assertThat(restTemplate.getForEntity("/api/batches/" + batchId, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ queue

    @Test
    void jobsStartInFifoOrderOfAcceptance() throws Exception {
        long batchId = createBatch("fifo");
        List<Long> ids = new ArrayList<>();
        for (String name : Arrays.asList("first", "second", "third")) {
            ids.add(json(uploadFile(batchId, syntheticPdf("fifo-" + name), name + ".pdf", null).getBody()).get("invoiceId").asLong());
        }
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            String text = new String((byte[]) inv.getArgument(0), StandardCharsets.US_ASCII);
            seen.add(text.substring(text.indexOf("fifo-"), text.indexOf("fifo-") + 10).trim());
            return new LlmExtractionResponse(validResult(UUID.randomUUID().toString().substring(0, 8)), null);
        }).when(llmInvoiceExtractor).extract(any(byte[].class));

        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).isFalse();

        assertThat(seen).containsExactly("fifo-first", "fifo-secon", "fifo-third");
        for (Long id : ids) {
            assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, id)).isEqualTo("NEEDS_REVIEW");
        }
    }

    @Test
    void twoConcurrentClaimsNeverGetTheSameJob() throws Exception {
        long batchId = createBatch("skip-locked");
        uploadFile(batchId, syntheticPdf("lock-a"), "a.pdf", null);
        uploadFile(batchId, syntheticPdf("lock-b"), "b.pdf", null);

        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> first = pool.submit(() -> transactionTemplate.execute(s -> {
                List<Long> ids = jobRepository.lockNextEligible(LocalDateTime.now());
                firstHoldsLock.countDown();
                await(secondDone);          // keep the row lock until the second claim has run
                return ids;
            }));
            assertThat(firstHoldsLock.await(20, TimeUnit.SECONDS)).isTrue();
            Future<List<Long>> second = pool.submit(() -> transactionTemplate.execute(s -> jobRepository.lockNextEligible(LocalDateTime.now())));
            List<Long> secondIds = second.get(20, TimeUnit.SECONDS); // must NOT wait for the first lock
            secondDone.countDown();
            List<Long> firstIds = first.get(20, TimeUnit.SECONDS);

            assertThat(firstIds).hasSize(1);
            assertThat(secondIds).hasSize(1);
            assertThat(secondIds.get(0)).isNotEqualTo(firstIds.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void extractionWithProblemsIsKeptForReviewWithReasonCodesAndTheRawAnswer() throws Exception {
        long batchId = createBatch("review");
        long id = json(uploadFile(batchId, syntheticPdf("problems"), "problems.pdf", null).getBody()).get("invoiceId").asLong();
        InvoiceExtractionResult bad = validResult("PROB-" + UUID.randomUUID().toString().substring(0, 6));
        bad.setInvoiceDate("06/10/2026");
        bad.setTaxAmount(new BigDecimal("18.005"));
        bad.setTotalAmount(new BigDecimal("999.00"));
        bad.setRawResponse("{\"raw\":\"kept for the reviewer\"}");
        doReturn(new LlmExtractionResponse(bad, null)).when(llmInvoiceExtractor).extract(any(byte[].class));

        assertThat(worker.processNextJob()).isTrue();

        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, id)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select invoice_date from invoices where id = ?", Object.class, id)).isNull();
        assertThat(jdbc.queryForObject("select tax_amount from invoices where id = ?", Object.class, id)).isNull();
        assertThat(jdbc.queryForObject("select total_amount from invoices where id = ?", BigDecimal.class, id)).isEqualByComparingTo("999.00");
        assertThat(jdbc.queryForObject("select raw_llm_response from invoices where id = ?", String.class, id)).contains("kept for the reviewer");
        assertThat(jdbc.queryForList("select code from invoice_review_reasons where invoice_id = ? order by id", String.class, id))
                .containsExactly("INVALID_DATE", "TOTAL_MISMATCH", "AMOUNT_PRECISION");
        assertThat(jdbc.queryForObject("select count(*) from invoice_versions where invoice_id = ? and version_number = 1 "
                + "and change_source = 'LLM_EXTRACTION' and changed_by is null", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from invoice_items where invoice_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(events(id)).containsExactly("null->QUEUED by user:" + vendor.getId(), "QUEUED->PROCESSING by system",
                "PROCESSING->NEEDS_REVIEW by system");
    }

    @Test
    void aCleanExtractionAlsoWaitsForAHumanReviewer() throws Exception {
        long batchId = createBatch("clean");
        long id = json(uploadFile(batchId, syntheticPdf("clean"), "clean.pdf", null).getBody()).get("invoiceId").asLong();
        doReturn(new LlmExtractionResponse(validResult("CLEAN-" + UUID.randomUUID().toString().substring(0, 6)), null)).when(llmInvoiceExtractor).extract(any(byte[].class));

        worker.processNextJob();

        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, id)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from invoice_review_reasons where invoice_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void providerFailureMarksTheInvoiceFailedButKeepsThePdf() throws Exception {
        long batchId = createBatch("failure");
        long id = json(uploadFile(batchId, syntheticPdf("fails"), "fails.pdf", null).getBody()).get("invoiceId").asLong();
        doThrow(new InvoiceExtractionException("Gemini rejected the request (HTTP 400). See server log for details.")).when(llmInvoiceExtractor).extract(any(byte[].class));

        worker.processNextJob();

        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_error_code from extraction_jobs where invoice_id = ?", String.class, id))
                .isNotBlank();
        String docId = jdbc.queryForObject("select source_document_id from invoices where id = ?", String.class, id);
        assertThat(restTemplate.getForEntity("/api/documents/" + docId, byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anInterruptedClaimIsRecoveredBackIntoTheQueue() throws Exception {
        long batchId = createBatch("stale");
        long id = json(uploadFile(batchId, syntheticPdf("stale"), "stale.pdf", null).getBody()).get("invoiceId").asLong();
        assertThat(jobService.claimNext("test-worker-that-dies").getInvoiceId()).isEqualTo(id);
        // Shift in SQL: the application stores UTC wall-clock times (hibernate.jdbc.time_zone=UTC), while a
        // LocalDateTime bound through plain JDBC would be written in the JVM's local time.
        jdbc.update("update extraction_jobs set claimed_at = claimed_at - interval '2 hours' where invoice_id = ?", id);

        assertThat(jobService.recoverStaleClaims()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error_code from extraction_jobs where invoice_id = ?", String.class, id))
                .isEqualTo("STALE_CLAIM");
    }

    // ------------------------------------------------------------------ helpers

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
