package com.actify.invoice.pgtest;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.queue.ExtractionJobService;
import com.actify.invoice.queue.ExtractionJobService.ClaimedJob;
import com.actify.invoice.queue.ExtractionOutcomeService;
import com.actify.invoice.queue.ExtractionWorker;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.user.Role;
import com.actify.invoice.workflow.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FREE_TIER_TEST mode against the real queue SQL (test database only; the LLM extractor is a Mockito mock, so nothing
 * is sent to Gemini). Proves on PostgreSQL that with one allowlisted invoice:
 *  - claiming never touches an older queued invoice outside the allowlist;
 *  - stale-claim and in-flight recovery change only the allowlisted invoice, and the test invoice is not re-queued;
 *  - an invoice that already had a Gemini call is never claimed again;
 *  - the worker makes one call and the failed test invoice stays FAILED.
 *
 * The free-tier services are built here (with the transaction advice Spring would add) because the allowlist is read
 * at construction and the test invoice id only exists once the test has uploaded it.
 */
class FreeTierQueuePgIntegrationTest extends AbstractQueuePgTest {

    @Autowired private ExtractionJobService jobService;
    @Autowired private ExtractionJobRepository jobRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private WorkflowService workflow;
    @Autowired private GeminiQuotaGuard quotaGuard;
    @Autowired private ExtractionOutcomeService outcomes;
    @Autowired private InvoiceDocumentStorage documentStorage;
    @Autowired private QuotaSettings quotaSettings;
    @Autowired private GeminiConfig geminiConfig;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void drainQueueAndLoginAsVendor() {
        drainQueue();
        actAs(createUser(Role.VENDOR));
    }

    /** Leave no QUEUED or PROCESSING job behind for the next test class. */
    @AfterEach
    void leaveTheQueueEmpty() {
        jdbc.update("update extraction_jobs set claimed_at = claimed_at - interval '2 hours' where invoice_id in "
                + "(select id from invoices where status = 'PROCESSING' and source_file_name like 'ft-%')");
        jobService.recoverStaleClaims();
        drainQueue();
    }

    private GeminiConfig freeTier() {
        return new GeminiConfig("not-used", geminiConfig.getModel(), "http://127.0.0.1:9/no-live-gemini-in-tests",
                true, false, true);
    }

    /** An ExtractionJobService in FREE_TIER_TEST mode for exactly this invoice, with @Transactional applied. */
    private ExtractionJobService freeTierJobs(long onlyInvoiceId) {
        ExtractionJobService target = new ExtractionJobService(jobRepository, invoiceRepository, workflow, quotaGuard,
                freeTier(), 10, String.valueOf(onlyInvoiceId));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor((TransactionManager) transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ExtractionJobService) proxy.getProxy();
    }

    /** Every column of the invoice, its job and its Gemini attempts, plus its workflow event count. */
    private String snapshot(long invoiceId) {
        return jdbc.queryForObject("select (select row_to_json(i)::text from invoices i where i.id = ?) || ' | ' "
                + "|| (select row_to_json(j)::text from extraction_jobs j where j.invoice_id = ?) || ' | ' "
                + "|| (select coalesce(json_agg(a order by a.id)::text, '[]') from gemini_call_attempts a where a.invoice_id = ?) "
                + "|| ' | events=' || (select count(*) from invoice_workflow_events e where e.invoice_id = ?)",
                String.class, invoiceId, invoiceId, invoiceId, invoiceId);
    }

    private int attempts(long invoiceId) {
        return jdbc.queryForObject("select count(*) from gemini_call_attempts where invoice_id = ?", Integer.class, invoiceId);
    }

    private void makeClaimStale(long invoiceId) {
        // Shift in SQL: the application stores UTC wall-clock times (see BatchQueuePgIntegrationTest).
        jdbc.update("update extraction_jobs set claimed_at = claimed_at - interval '2 hours' where invoice_id = ?", invoiceId);
        jdbc.update("update gemini_call_attempts set started_at = started_at - interval '2 hours' "
                + "where invoice_id = ? and outcome = 'IN_FLIGHT'", invoiceId);
    }

    @Test
    void claimingIgnoresAnOlderQueuedInvoiceOutsideTheAllowlist() throws Exception {
        long other = queueOneInvoice(unique("ft-other"));   // older: FIFO would start it first
        long target = queueOneInvoice(unique("ft-target"));
        String otherBefore = snapshot(other);
        ExtractionJobService freeTierJobs = freeTierJobs(target);

        ClaimedJob claimed = freeTierJobs.claimNext("free-tier-test-worker");

        assertThat(claimed).isNotNull();
        assertThat(claimed.getInvoiceId()).isEqualTo(target);
        assertThat(status(target)).isEqualTo("PROCESSING");
        assertThat(freeTierJobs.claimNext("free-tier-test-worker")).as("nothing else is ever claimed").isNull();
        assertThat(status(other)).isEqualTo("QUEUED");
        assertThat(snapshot(other)).as("the other invoice is untouched").isEqualTo(otherBefore);
    }

    @Test
    void staleRecoveryChangesOnlyTheAllowlistedInvoiceAndDoesNotRequeueIt() throws Exception {
        long other = queueOneInvoice(unique("ft-other"));
        long target = queueOneInvoice(unique("ft-target"));
        // Both claimed by an ordinary worker that then "died": both PROCESSING, both with an IN_FLIGHT attempt.
        assertThat(jobService.claimNext("dead-worker").getInvoiceId()).isEqualTo(other);
        assertThat(jobService.claimNext("dead-worker").getInvoiceId()).isEqualTo(target);
        makeClaimStale(other);
        makeClaimStale(target);
        String otherBefore = snapshot(other);
        ExtractionJobService freeTierJobs = freeTierJobs(target);

        assertThat(quotaGuard.recoverInFlight(Collections.singleton(target))).isEqualTo(1);
        assertThat(freeTierJobs.recoverStaleClaims()).isEqualTo(1);

        // The test invoice had attempts left, but the interrupted call may have reached Gemini: FAILED, not re-queued.
        assertThat(status(target)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_error_code from extraction_jobs where invoice_id = ?", String.class, target))
                .isEqualTo("STALE_CLAIM");
        assertThat(jdbc.queryForObject("select outcome from gemini_call_attempts where invoice_id = ?", String.class, target))
                .isEqualTo("UNKNOWN");
        assertThat(status(other)).isEqualTo("PROCESSING");
        assertThat(snapshot(other)).as("the other invoice, its job and its attempt are untouched").isEqualTo(otherBefore);
    }

    @Test
    void anInvoiceThatWasAlreadySentOnceIsNeverClaimedAgain() throws Exception {
        long target = queueOneInvoice(unique("ft-target"));
        // An earlier ordinary run claimed it (attempt 1 recorded), died, and the invoice went back to QUEUED.
        assertThat(jobService.claimNext("dead-worker").getInvoiceId()).isEqualTo(target);
        makeClaimStale(target);
        jobService.recoverStaleClaims();
        assertThat(status(target)).isEqualTo("QUEUED");
        String before = snapshot(target);
        ExtractionJobService freeTierJobs = freeTierJobs(target);

        assertThat(freeTierJobs.claimNext("free-tier-test-worker")).isNull();

        assertThat(freeTierJobs.getLastQuotaReason()).contains("already sent to Gemini once");
        assertThat(snapshot(target)).as("refusing changes nothing").isEqualTo(before);
    }

    @Test
    void theFreeTierWorkerMakesOneCallAndAFailureIsFinal() throws Exception {
        long other = queueOneInvoice(unique("ft-other"));
        long target = queueOneInvoice(unique("ft-target"));
        String otherBefore = snapshot(other);
        ExtractionJobService freeTierJobs = freeTierJobs(target);
        ExtractionWorker worker = new ExtractionWorker(freeTierJobs, outcomes, documentStorage, llmInvoiceExtractor,
                quotaGuard, quotaSettings, freeTier(), true);
        // A transient error that a paid run would retry with back-off.
        extractorThrows(new InvoiceExtractionException("Gemini request failed with HTTP 503",
                InvoiceExtractionException.Kind.HTTP_ERROR, 503, null, null, null));

        assertThat(worker.processNextJob()).isTrue();
        assertThat(worker.processNextJob()).isFalse();
        worker.poll();

        verify(llmInvoiceExtractor, times(1)).extract(any(byte[].class));
        assertThat(status(target)).isEqualTo("FAILED");
        assertThat(attempts(target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_reason from invoices where id = ?", String.class, target))
                .contains("free-tier test: no automatic retry");
        assertThat(worker.getHaltReason()).contains("invoice " + target).contains("HTTP_503");
        assertThat(snapshot(other)).as("the other invoice is untouched").isEqualTo(otherBefore);
    }
}
