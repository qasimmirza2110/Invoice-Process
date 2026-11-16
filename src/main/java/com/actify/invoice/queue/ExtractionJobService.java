package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.GeminiCallAttempt;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Short database transactions around the queue. The Gemini call itself is NEVER inside one of these:
 * claimNext() commits before the call, ExtractionOutcomeService writes the result in a new transaction after it.
 *
 * The queue is discovered from PostgreSQL alone - there is no invoice-id allowlist: every QUEUED invoice of a batch
 * with the uploader's free-tier consent is taken, oldest first. Invoices without consent stay QUEUED and are never sent.
 */
@Service
public class ExtractionJobService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionJobService.class);

    private final ExtractionJobRepository jobRepository;
    private final InvoiceRepository invoiceRepository;
    private final WorkflowService workflow;
    private final GeminiQuotaGuard quotaGuard;
    private final GeminiConfig geminiConfig;
    private final int staleClaimMinutes;
    private volatile String lastQuotaReason;
    private volatile LocalDateTime lastQuotaNotBefore;

    public ExtractionJobService(ExtractionJobRepository jobRepository, InvoiceRepository invoiceRepository,
                                WorkflowService workflow, GeminiQuotaGuard quotaGuard, GeminiConfig geminiConfig,
                                @Value("${invoice.worker.stale-claim-minutes:10}") int staleClaimMinutes) {
        this.jobRepository = jobRepository;
        this.invoiceRepository = invoiceRepository;
        this.workflow = workflow;
        this.quotaGuard = quotaGuard;
        this.geminiConfig = geminiConfig;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    /** QUEUED invoices with free-tier consent that the worker could start now (back-off passed). Read-only. */
    @Transactional(readOnly = true)
    public long countEligibleNow() {
        return jobRepository.countFreeTierEligible(LocalDateTime.now());
    }

    /** QUEUED invoices with free-tier consent whose next attempt is later (retry back-off, 429 or daily-quota wait). */
    @Transactional(readOnly = true)
    public long countDeferred() {
        return jobRepository.countFreeTierDeferred(LocalDateTime.now());
    }

    /** QUEUED invoices that are never sent because their batch has no free-tier consent. */
    @Transactional(readOnly = true)
    public long countQueuedWithoutFreeTierConsent() {
        return jobRepository.countQueuedWithoutFreeTierConsent();
    }

    /** True only for a batch invoice whose batch carries the uploader's free-tier consent. */
    static boolean hasFreeTierConsent(Invoice invoice) {
        return invoice.getBatch() != null && invoice.getBatch().isFreeTierConsent();
    }

    /** Re-reads the consent right before the PDF is sent (the worker's last check outside the claim). */
    @Transactional(readOnly = true)
    public boolean hasFreeTierConsent(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        return invoice != null && hasFreeTierConsent(invoice);
    }

    /** When the last refused claim may be tried again (quota pacing or pause), or null when unknown / not refused. */
    public LocalDateTime getLastQuotaNotBefore() {
        return lastQuotaNotBefore;
    }

    /**
     * In ONE short transaction:
     *   1. lock the oldest eligible job (QUEUED, back-off passed, batch WITH free-tier consent - enforced in SQL);
     *   2. check the consent again on the entity (defence in depth);
     *   3. ask the shared quota guard whether a Gemini call for THIS PDF is allowed now (advisory lock held until
     *      commit; the token reservation is scaled to the PDF's size);
     *   4. mark the job PROCESSING and record the Gemini attempt as IN_FLIGHT.
     * The row lock (FOR UPDATE SKIP LOCKED) and the advisory lock end when this transaction commits -
     * before the slow Gemini call starts. A refusal changes nothing: the invoice simply stays QUEUED.
     *
     * @return what the worker needs for the extraction, or null when quota or the queue says "not now"
     */
    @Transactional
    public ClaimedJob claimNext(String workerName) {
        if (!geminiConfig.isFreeTierAutoQueue()) {
            lastQuotaReason = geminiConfig.getBlockedReason();
            lastQuotaNotBefore = null;
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = jobRepository.lockNextFreeTierEligible(now);
        if (ids.isEmpty()) {
            lastQuotaReason = null;
            lastQuotaNotBefore = null;
            return null;
        }
        ExtractionJob job = jobRepository.findById(ids.get(0)).orElseThrow(IllegalStateException::new);
        Invoice invoice = job.getInvoice();
        if (!hasFreeTierConsent(invoice)) {
            // Defence in depth behind the SQL filter: never send an invoice without the uploader's consent.
            log.error("Free-tier queue refused invoice {}: its batch has no free-tier consent", invoice.getId());
            lastQuotaReason = "Invoice " + invoice.getId() + " has no free-tier consent and is not sent.";
            return null;
        }
        GeminiQuotaGuard.Check check = quotaGuard.check(invoice.getSourceDocument().getSizeBytes());
        if (!check.isAllowed()) {
            lastQuotaReason = check.getReason();
            lastQuotaNotBefore = check.getNotBefore();
            return null;
        }
        lastQuotaReason = null;
        lastQuotaNotBefore = null;
        job.claim(now, workerName);
        workflow.transition(invoice, InvoiceStatus.PROCESSING, null, null, "Attempt " + job.getAttemptCount());
        GeminiCallAttempt attempt = quotaGuard.recordStart(invoice, GeminiCallAttempt.Caller.WORKER,
                check.getEstimatedInputTokens());
        return new ClaimedJob(invoice.getId(), invoice.getSourceDocument().getId(), invoice.getSourceDocument().getSha256(),
                job.getAttemptCount(), job.hasAttemptsLeft(), attempt.getId());
    }

    /** Why the last claim was refused by the quota guard or the data-policy gate (for the admin dashboard), or null. */
    public String getLastQuotaReason() {
        return lastQuotaReason;
    }

    /**
     * A PROCESSING job claimed longer ago than the stale limit belongs to a worker that died (crash, restart, Render
     * spin-down). It goes back to QUEUED (keeping its FIFO position) or to FAILED when it has no attempts left.
     * The interrupted attempt still counts: Gemini may already have used quota for it.
     */
    @Transactional
    public int recoverStaleClaims() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(staleClaimMinutes);
        List<ExtractionJob> stale = jobRepository.findStaleClaims(cutoff);
        int recovered = 0;
        for (ExtractionJob job : stale) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(job.getInvoiceId()).orElse(null);
            if (invoice == null || invoice.getStatus() != InvoiceStatus.PROCESSING) {
                continue;
            }
            if (job.hasAttemptsLeft()) {
                job.scheduleRetry(now, "STALE_CLAIM", "The previous extraction attempt was interrupted.");
                workflow.transition(invoice, InvoiceStatus.QUEUED, null, null, "Recovered after an interrupted extraction");
            } else {
                String reason = "The extraction was interrupted and no attempts are left.";
                job.fail(now, "STALE_CLAIM", reason);
                invoice.setFailureReason(reason);
                workflow.transition(invoice, InvoiceStatus.FAILED, null, null, "STALE_CLAIM");
            }
            recovered++;
        }
        if (recovered > 0) {
            log.warn("Recovered {} interrupted extraction job(s)", recovered);
        }
        return recovered;
    }

    /** What a claimed job needs outside the transaction: which PDF to read, and the attempt number. */
    public static final class ClaimedJob {
        private final Long invoiceId;
        private final String documentId;
        private final String sha256;
        private final int attemptNumber;
        private final boolean attemptsLeftAfterThis;
        private final Long geminiAttemptId;

        public ClaimedJob(Long invoiceId, String documentId, String sha256, int attemptNumber,
                          boolean attemptsLeftAfterThis, Long geminiAttemptId) {
            this.invoiceId = invoiceId;
            this.documentId = documentId;
            this.sha256 = sha256;
            this.attemptNumber = attemptNumber;
            this.attemptsLeftAfterThis = attemptsLeftAfterThis;
            this.geminiAttemptId = geminiAttemptId;
        }

        public Long getInvoiceId() { return invoiceId; }
        public String getDocumentId() { return documentId; }
        public String getSha256() { return sha256; }
        /** 1 for the first try of this job, 2 for the first retry... */
        public int getAttemptNumber() { return attemptNumber; }
        /** False when this is the job's last allowed attempt. */
        public boolean hasAttemptsLeftAfterThis() { return attemptsLeftAfterThis; }
        public Long getGeminiAttemptId() { return geminiAttemptId; }
    }
}
