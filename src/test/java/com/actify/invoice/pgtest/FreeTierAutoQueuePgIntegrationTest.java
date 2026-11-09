package com.actify.invoice.pgtest;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.UploadBatch;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.queue.ExtractionJobService;
import com.actify.invoice.queue.ExtractionJobService.ClaimedJob;
import com.actify.invoice.queue.ExtractionOutcomeService;
import com.actify.invoice.queue.ExtractionWorker;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.UploadBatchRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.user.Role;
import com.actify.invoice.workflow.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The free-tier queue against the real queue SQL (test database only; the LLM extractor is a Mockito mock, so nothing
 * is sent to Gemini). Own fake model id, so the quota rows of this class cannot slow down another class.
 * Proves on PostgreSQL, with NO invoice id configured anywhere:
 *  - consented uploads are discovered and reach NEEDS_REVIEW oldest-first;
 *  - an older upload WITHOUT consent (and a pre-migration batch) is never claimed and never sent;
 *  - the consent is stored with its time and cannot be changed afterwards;
 *  - a per-minute 429 keeps the invoice QUEUED with its attempts and cools the queue down;
 *  - a daily-quota pause survives a worker restart, keeps every invoice QUEUED, and resumes after the reset;
 *  - reported token usage is persisted;
 *  - two concurrent claims never get the same invoice.
 */
class FreeTierAutoQueuePgIntegrationTest extends AbstractQueuePgTest {

    private static final String MODEL = "pgtest-auto-queue-model-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void ownModel(DynamicPropertyRegistry registry) {
        registry.add("gemini.model", () -> MODEL);
    }

    @Autowired private ExtractionJobRepository jobRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private UploadBatchRepository batchRepository;
    @Autowired private WorkflowService workflow;
    @Autowired private GeminiQuotaGuard quotaGuard;
    @Autowired private ExtractionOutcomeService outcomes;
    @Autowired private InvoiceDocumentStorage documentStorage;
    @Autowired private QuotaSettings quotaSettings;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void drainQueueAndLoginAsVendor() {
        drainQueue();
        actAs(createUser(Role.VENDOR));
    }

    /** No cool-down, daily pause, deferred or PROCESSING job of this class is left for the next test. */
    @AfterEach
    void leaveTheQueueEmpty() {
        jdbc.update("update gemini_call_attempts set started_at = started_at - interval '3 days', "
                + "finished_at = finished_at - interval '3 days' where model_id = ?", MODEL);
        jdbc.update("update extraction_jobs set next_attempt_at = next_attempt_at - interval '3 days' where invoice_id in "
                + "(select id from invoices where status = 'QUEUED' and source_file_name like 'aq-%')");
        jdbc.update("update extraction_jobs set claimed_at = claimed_at - interval '2 hours' where invoice_id in "
                + "(select id from invoices where status = 'PROCESSING' and source_file_name like 'aq-%')");
        freshJobs().recoverStaleClaims();
        drainQueue();
    }

    private static GeminiConfig freeTier() {
        return new GeminiConfig("not-used", MODEL, "http://127.0.0.1:9/no-live-gemini-in-tests", true);
    }

    /** A NEW ExtractionJobService (= a restarted process), with @Transactional applied. No invoice id is passed. */
    private ExtractionJobService freshJobs() {
        ExtractionJobService target = new ExtractionJobService(jobRepository, invoiceRepository, workflow, quotaGuard,
                freeTier(), 10);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor((TransactionManager) transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ExtractionJobService) proxy.getProxy();
    }

    /** A NEW worker (= after a restart of the service). */
    private ExtractionWorker freshWorker() {
        return new ExtractionWorker(freshJobs(), outcomes, documentStorage, llmInvoiceExtractor, quotaGuard, quotaSettings,
                freeTier(), true);
    }

    /** Creates a batch through the API WITH freeTierProcessingConsent=true and uploads one synthetic PDF into it. */
    private long queueConsented(String marker) throws Exception {
        long batchId = createBatch(marker, true);
        ResponseEntity<String> upload = uploadFile(batchId, syntheticPdf(marker), marker + ".pdf", null);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return json(upload.getBody()).get("invoiceId").asLong();
    }

    private long queueWithoutConsent(String marker) throws Exception {
        long batchId = createBatch(marker, false);
        ResponseEntity<String> upload = uploadFile(batchId, syntheticPdf(marker), marker + ".pdf", null);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return json(upload.getBody()).get("invoiceId").asLong();
    }

    private String snapshot(long invoiceId) {
        return jdbc.queryForObject("select (select row_to_json(i)::text from invoices i where i.id = ?) || ' | ' "
                + "|| (select row_to_json(j)::text from extraction_jobs j where j.invoice_id = ?) || ' | ' "
                + "|| (select coalesce(json_agg(a order by a.id)::text, '[]') from gemini_call_attempts a where a.invoice_id = ?)",
                String.class, invoiceId, invoiceId, invoiceId);
    }

    @Test
    void consentedUploadsAreDiscoveredAndProcessedOldestFirstWithoutConfiguringTheirIds() throws Exception {
        long first = queueConsented(unique("aq-first"));
        long second = queueConsented(unique("aq-second"));
        long third = queueConsented(unique("aq-third"));
        extractorReturns(validResult(unique("AQ")), new TokenUsage());
        ExtractionWorker worker = freshWorker();
        assertThat(worker.isRunning()).isTrue();

        assertThat(worker.processNextJob()).isTrue();
        assertThat(status(first)).as("FIFO: the oldest upload goes first").isEqualTo("NEEDS_REVIEW");
        assertThat(status(second)).isEqualTo("QUEUED");
        assertThat(worker.processNextJob()).isTrue();
        assertThat(status(second)).isEqualTo("NEEDS_REVIEW");
        assertThat(status(third)).isEqualTo("QUEUED");
        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).isFalse();

        assertThat(status(third)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from gemini_call_attempts where invoice_id in (?, ?, ?) "
                + "and outcome = 'SUCCESS' and model_id = ?", Integer.class, first, second, third, MODEL)).isEqualTo(3);
        verify(llmInvoiceExtractor, times(3)).extract(any(byte[].class));
    }

    @Test
    void anOlderUploadWithoutConsentIsSkippedAndNeverSent() throws Exception {
        long withoutConsent = queueWithoutConsent(unique("aq-noconsent")); // older: plain FIFO would start it first
        long consented = queueConsented(unique("aq-consent"));
        String before = snapshot(withoutConsent);
        extractorReturns(validResult(unique("AQ")), new TokenUsage());
        ExtractionJobService jobs = freshJobs();
        ExtractionWorker worker = new ExtractionWorker(jobs, outcomes, documentStorage, llmInvoiceExtractor, quotaGuard,
                quotaSettings, freeTier(), true);

        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).as("nothing else is eligible").isFalse();

        assertThat(status(consented)).isEqualTo("NEEDS_REVIEW");
        assertThat(status(withoutConsent)).isEqualTo("QUEUED");
        assertThat(snapshot(withoutConsent)).as("never claimed, no Gemini attempt").isEqualTo(before);
        assertThat(jobs.countEligibleNow()).isZero();
        assertThat(jobs.countQueuedWithoutFreeTierConsent()).isGreaterThanOrEqualTo(1);
        verify(llmInvoiceExtractor, times(1)).extract(any(byte[].class));
    }

    @Test
    void aBatchCreatedWithoutTheFieldIsNotConsentedAndPreMigrationRowsDefaultToFalse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.postForEntity("/api/batches",
                new HttpEntity<>("{\"label\":\"aq-default\"}", headers), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(created.getBody()).get("freeTierProcessingConsent").asBoolean()).isFalse();
        assertThat(json(created.getBody()).get("freeTierProcessingConsentAt").isNull()).isTrue();
        // A row inserted the way pre-migration rows exist (without the column) gets the column default: false.
        assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'upload_batches' and column_name = 'free_tier_consent'", String.class)).isEqualTo("false");
    }

    @Test
    void theConsentIsStoredWithItsTimeAndCannotBeChangedAfterwards() throws Exception {
        long invoice = queueConsented(unique("aq-immutable"));
        long batchId = jdbc.queryForObject("select batch_id from invoices where id = ?", Long.class, invoice);
        assertThat(jdbc.queryForObject("select free_tier_consent_at between created_at - interval '1 second' and created_at "
                + "+ interval '1 second' from upload_batches where id = ?", Boolean.class, batchId)).isTrue();
        String consentAt = jdbc.queryForObject("select free_tier_consent_at::text from upload_batches where id = ?",
                String.class, batchId);

        // Even code that changes the entity cannot change the stored consent: the columns are not updatable.
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            UploadBatch batch = batchRepository.findById(batchId).orElseThrow(IllegalStateException::new);
            ReflectionTestUtils.setField(batch, "freeTierConsent", false);
            ReflectionTestUtils.setField(batch, "freeTierConsentAt", null);
            batch.close(); // a real change, so Hibernate does issue an UPDATE for this row
        });

        assertThat(jdbc.queryForObject("select status from upload_batches where id = ?", String.class, batchId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select free_tier_consent from upload_batches where id = ?", Boolean.class, batchId))
                .isTrue();
        assertThat(jdbc.queryForObject("select free_tier_consent_at::text from upload_batches where id = ?",
                String.class, batchId)).isEqualTo(consentAt);
    }

    @Test
    void aPerMinute429KeepsTheInvoiceQueuedWithItsAttemptsAndCoolsTheQueueDown() throws Exception {
        long first = queueConsented(unique("aq-429a"));
        long second = queueConsented(unique("aq-429b"));
        extractorThrows(new InvoiceExtractionException("Gemini rate limit (HTTP 429)",
                InvoiceExtractionException.Kind.HTTP_ERROR, 429, "RESOURCE_EXHAUSTED", null, null));
        ExtractionWorker worker = freshWorker();

        assertThat(worker.processNextJob()).isTrue();

        assertThat(status(first)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, first))
                .as("a rate limit does not use up an attempt").isZero();
        // Deferred by the cool-down (time-zone independent: compared with its own queue time, seconds ago).
        assertThat(jdbc.queryForObject("select next_attempt_at > queued_at + interval '30 seconds' from extraction_jobs "
                + "where invoice_id = ?", Boolean.class, first)).isTrue();
        assertThat(jdbc.queryForObject("select outcome from gemini_call_attempts where invoice_id = ?", String.class, first))
                .isEqualTo("RETRYABLE_ERROR");
        // The cool-down holds the whole queue: no tight retry loop, the next invoice is not sent into the same 429.
        for (int i = 0; i < 5; i++) {
            assertThat(worker.processNextJob()).isFalse();
        }
        assertThat(status(second)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from gemini_call_attempts where invoice_id = ?", Integer.class, second))
                .isZero();
        verify(llmInvoiceExtractor, times(1)).extract(any(byte[].class));
    }

    @Test
    void aDailyQuotaPauseSurvivesARestartAndResumesAfterTheReset() throws Exception {
        long first = queueConsented(unique("aq-rpd-a"));
        long second = queueConsented(unique("aq-rpd-b"));
        extractorThrows(new InvoiceExtractionException("daily quota", InvoiceExtractionException.Kind.HTTP_ERROR, 429,
                "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel-FreeTier", null, null));

        assertThat(freshWorker().processNextJob()).isTrue();
        assertThat(status(first)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select outcome from gemini_call_attempts where invoice_id = ?", String.class, first))
                .isEqualTo("DAILY_QUOTA");

        // "Restart": a brand-new worker reads the pause from PostgreSQL and sends nothing; nothing became FAILED.
        ExtractionWorker restarted = freshWorker();
        assertThat(restarted.processNextJob()).isFalse();
        assertThat(quotaGuard.status().getPausedUntil()).isNotNull();
        assertThat(quotaGuard.status().isDailyBudgetExhausted()).isTrue();
        assertThat(status(first)).isEqualTo("QUEUED");
        assertThat(status(second)).isEqualTo("QUEUED");
        verify(llmInvoiceExtractor, times(1)).extract(any(byte[].class));

        // Midnight Pacific time passes (simulated by moving this model's rows and the deferral into the past).
        jdbc.update("update gemini_call_attempts set started_at = started_at - interval '2 days', "
                + "finished_at = finished_at - interval '2 days' where model_id = ?", MODEL);
        jdbc.update("update extraction_jobs set next_attempt_at = next_attempt_at - interval '2 days' where invoice_id = ?",
                first);
        extractorReturns(validResult(unique("AQ")), new TokenUsage());

        ExtractionWorker afterReset = freshWorker();
        assertThat(afterReset.processNextJob()).isTrue();
        assertThat(afterReset.processNextJob()).isTrue();
        assertThat(status(first)).isEqualTo("NEEDS_REVIEW");
        assertThat(status(second)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void reportedTokenUsageIsPersistedOnTheAttemptAndTheInvoice() throws Exception {
        long invoice = queueConsented(unique("aq-tokens"));
        TokenUsage usage = new TokenUsage();
        usage.setModelId(MODEL);
        usage.setPromptTokenCount(1290L);
        usage.setCandidatesTokenCount(310L);
        usage.setThoughtsTokenCount(40L);
        usage.setTotalTokenCount(1640L);
        extractorReturns(validResult(unique("AQ")), usage);

        assertThat(freshWorker().processNextJob()).isTrue();

        assertThat(jdbc.queryForObject("select prompt_token_count || '/' || candidates_token_count || '/' || "
                + "thoughts_token_count || '/' || total_token_count from gemini_call_attempts where invoice_id = ?",
                String.class, invoice)).isEqualTo("1290/310/40/1640");
        assertThat(jdbc.queryForObject("select total_token_count from invoices where id = ?", Long.class, invoice))
                .isEqualTo(1640L);
        assertThat(quotaGuard.status().getTotalTokensSinceDailyReset()).isGreaterThanOrEqualTo(1640L);
        assertThat(quotaGuard.status().getOutputTokensSinceDailyReset()).isGreaterThanOrEqualTo(350L);
    }

    @Test
    void twoConcurrentClaimsNeverGetTheSameInvoice() throws Exception {
        long first = queueConsented(unique("aq-c1"));
        long second = queueConsented(unique("aq-c2"));
        ExtractionJobService jobs = freshJobs();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ClaimedJob>> claims = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String name = "concurrent-" + i;
                claims.add(pool.submit((Callable<ClaimedJob>) () -> {
                    start.await();
                    return jobs.claimNext(name);
                }));
            }
            start.countDown();
            ClaimedJob a = claims.get(0).get(30, TimeUnit.SECONDS);
            ClaimedJob b = claims.get(1).get(30, TimeUnit.SECONDS);

            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
            assertThat(a.getInvoiceId()).isNotEqualTo(b.getInvoiceId());
            assertThat(new long[]{a.getInvoiceId(), b.getInvoiceId()}).containsExactlyInAnyOrder(first, second);
        } finally {
            pool.shutdownNow();
        }
    }
}
