package com.actify.invoice.queue;

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
 */
@Service
public class ExtractionJobService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionJobService.class);

    private final ExtractionJobRepository jobRepository;
    private final InvoiceRepository invoiceRepository;
    private final WorkflowService workflow;
    private final GeminiQuotaGuard quotaGuard;
    private final int staleClaimMinutes;
    private volatile String lastQuotaReason;

    public ExtractionJobService(ExtractionJobRepository jobRepository, InvoiceRepository invoiceRepository,
                                WorkflowService workflow, GeminiQuotaGuard quotaGuard,
                                @Value("${invoice.worker.stale-claim-minutes:10}") int staleClaimMinutes) {
        this.jobRepository = jobRepository;
        this.invoiceRepository = invoiceRepository;
        this.workflow = workflow;
        this.quotaGuard = quotaGuard;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    /**
     * In ONE short transaction:
     *   1. ask the shared quota guard whether a Gemini call is allowed now (advisory lock held until commit);
     *   2. claim the oldest eligible job (FIFO by queued_at, then invoice id) and mark it PROCESSING;
     *   3. record the Gemini attempt as IN_FLIGHT.
     * The row lock (FOR UPDATE SKIP LOCKED) and the advisory lock end when this transaction commits -
     * before the slow Gemini call starts.
     *
     * @return what the worker needs for the extraction, or null when quota or the queue says "not now"
     */
    @Transactional
    public ClaimedJob claimNext(String workerName) {
        GeminiQuotaGuard.Check check = quotaGuard.check(true);
        if (!check.isAllowed()) {
            lastQuotaReason = check.getReason();
            return null;
        }
        lastQuotaReason = null;
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = jobRepository.lockNextEligible(now);
        if (ids.isEmpty()) {
            return null;
        }
        ExtractionJob job = jobRepository.findById(ids.get(0)).orElseThrow(IllegalStateException::new);
        Invoice invoice = job.getInvoice();
        job.claim(now, workerName);
        workflow.transition(invoice, InvoiceStatus.PROCESSING, null, null, "Attempt " + job.getAttemptCount());
        GeminiCallAttempt attempt = quotaGuard.recordStart(invoice, GeminiCallAttempt.Caller.WORKER,
                check.getEstimatedInputTokens());
        return new ClaimedJob(invoice.getId(), invoice.getSourceDocument().getId(), invoice.getSourceDocument().getSha256(),
                job.getAttemptCount(), job.hasAttemptsLeft(), attempt.getId());
    }

    /** Why the last claim was refused by the quota guard (for the admin dashboard), or null. */
    public String getLastQuotaReason() {
        return lastQuotaReason;
    }

    /**
     * A PROCESSING job claimed longer ago than the stale limit belongs to a worker that died (crash, restart).
     * It goes back to QUEUED (keeping its FIFO position) or to FAILED when it has no attempts left.
     * The interrupted attempt still counts: Gemini may already have used quota for it.
     */
    @Transactional
    public int recoverStaleClaims() {
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (ExtractionJob job : jobRepository.findStaleClaims(now.minusMinutes(staleClaimMinutes))) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(job.getInvoiceId()).orElse(null);
            if (invoice == null || invoice.getStatus() != InvoiceStatus.PROCESSING) {
                continue;
            }
            if (job.hasAttemptsLeft()) {
                job.scheduleRetry(now, "STALE_CLAIM", "The previous extraction attempt was interrupted.");
                workflow.transition(invoice, InvoiceStatus.QUEUED, null, null, "Recovered after an interrupted extraction");
            } else {
                job.fail(now, "STALE_CLAIM", "The extraction was interrupted and no attempts are left.");
                invoice.setFailureReason("The extraction was interrupted and no attempts are left.");
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
