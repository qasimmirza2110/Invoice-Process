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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Short database transactions around the queue. The Gemini call itself is NEVER inside one of these:
 * claimNext() commits before the call, ExtractionOutcomeService writes the result in a new transaction after it.
 */
@Service
public class ExtractionJobService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionJobService.class);

    static final String FREE_TIER_NEEDS_ONE_INVOICE = "Free-tier test: INVOICE_WORKER_ONLY_INVOICE_IDS must name exactly "
            + "one invoice id (the selected test invoice); no job is claimed.";

    private final ExtractionJobRepository jobRepository;
    private final InvoiceRepository invoiceRepository;
    private final WorkflowService workflow;
    private final GeminiQuotaGuard quotaGuard;
    private final GeminiConfig geminiConfig;
    private final int staleClaimMinutes;
    private final Set<Long> onlyInvoiceIds;
    private volatile String lastQuotaReason;

    public ExtractionJobService(ExtractionJobRepository jobRepository, InvoiceRepository invoiceRepository,
                                WorkflowService workflow, GeminiQuotaGuard quotaGuard, GeminiConfig geminiConfig,
                                @Value("${invoice.worker.stale-claim-minutes:10}") int staleClaimMinutes,
                                @Value("${invoice.worker.only-invoice-ids:}") String onlyInvoiceIds) {
        this.jobRepository = jobRepository;
        this.invoiceRepository = invoiceRepository;
        this.workflow = workflow;
        this.quotaGuard = quotaGuard;
        this.geminiConfig = geminiConfig;
        this.staleClaimMinutes = staleClaimMinutes;
        this.onlyInvoiceIds = parseIds(onlyInvoiceIds);
    }

    /** "17, 18" -> {17, 18}; blank -> empty (= no restriction). A malformed list is refused at startup. */
    static Set<Long> parseIds(String raw) {
        Set<Long> ids = new TreeSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.unmodifiableSet(ids);
        }
        for (String part : raw.split(",")) {
            try {
                ids.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invoice.worker.only-invoice-ids (INVOICE_WORKER_ONLY_INVOICE_IDS) "
                        + "must be a comma-separated list of invoice ids");
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** The invoices the worker is limited to; empty = every queued invoice (normal operation). */
    public Set<Long> getOnlyInvoiceIds() {
        return onlyInvoiceIds;
    }

    /**
     * In ONE short transaction:
     *   1. ask the shared quota guard whether a Gemini call is allowed now (advisory lock held until commit);
     *   2. claim the oldest eligible job (FIFO by queued_at, then invoice id) and mark it PROCESSING;
     *   3. record the Gemini attempt as IN_FLIGHT.
     * The row lock (FOR UPDATE SKIP LOCKED) and the advisory lock end when this transaction commits -
     * before the slow Gemini call starts.
     *
     * FREE_TIER_TEST mode (GeminiConfig) adds two refusals that change nothing: without exactly one allowlisted invoice
     * no job is claimed at all (a second check behind SchemaSafetyGuard), and an invoice that already had a Gemini call
     * is never sent again (one attempt per test invoice, also across restarts).
     *
     * @return what the worker needs for the extraction, or null when quota or the queue says "not now"
     */
    @Transactional
    public ClaimedJob claimNext(String workerName) {
        boolean freeTierTest = geminiConfig.isFreeTierTest();
        if (freeTierTest && onlyInvoiceIds.size() != 1) {
            lastQuotaReason = FREE_TIER_NEEDS_ONE_INVOICE;
            return null;
        }
        GeminiQuotaGuard.Check check = quotaGuard.check(true);
        if (!check.isAllowed()) {
            lastQuotaReason = check.getReason();
            return null;
        }
        lastQuotaReason = null;
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = onlyInvoiceIds.isEmpty() ? jobRepository.lockNextEligible(now)
                : jobRepository.lockNextEligibleAmong(now, onlyInvoiceIds);
        if (ids.isEmpty()) {
            return null;
        }
        if (freeTierTest && quotaGuard.hasAnyAttempt(ids.get(0))) {
            lastQuotaReason = "Free-tier test: invoice " + ids.get(0) + " was already sent to Gemini once and is not "
                    + "sent again. Another test needs a NEW upload with its own invoice id.";
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

    /** Why the last claim was refused by the quota guard or a free-tier test rule (for the admin dashboard), or null. */
    public String getLastQuotaReason() {
        return lastQuotaReason;
    }

    /**
     * A PROCESSING job claimed longer ago than the stale limit belongs to a worker that died (crash, restart).
     * It goes back to QUEUED (keeping its FIFO position) or to FAILED when it has no attempts left.
     * The interrupted attempt still counts: Gemini may already have used quota for it.
     *
     * With an allowlist (invoice.worker.only-invoice-ids) only those invoices are recovered: a controlled test never
     * changes another invoice. In FREE_TIER_TEST mode the interrupted call may already have reached Gemini, so the
     * test invoice becomes FAILED instead of being sent again; without exactly one allowlisted id nothing is touched.
     */
    @Transactional
    public int recoverStaleClaims() {
        boolean freeTierTest = geminiConfig.isFreeTierTest();
        if (freeTierTest && onlyInvoiceIds.size() != 1) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(staleClaimMinutes);
        List<ExtractionJob> stale = onlyInvoiceIds.isEmpty() ? jobRepository.findStaleClaims(cutoff)
                : jobRepository.findStaleClaimsAmong(cutoff, onlyInvoiceIds);
        int recovered = 0;
        for (ExtractionJob job : stale) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(job.getInvoiceId()).orElse(null);
            if (invoice == null || invoice.getStatus() != InvoiceStatus.PROCESSING) {
                continue;
            }
            if (job.hasAttemptsLeft() && !freeTierTest) {
                job.scheduleRetry(now, "STALE_CLAIM", "The previous extraction attempt was interrupted.");
                workflow.transition(invoice, InvoiceStatus.QUEUED, null, null, "Recovered after an interrupted extraction");
            } else {
                String reason = freeTierTest
                        ? "The extraction was interrupted; the free-tier test does not send an invoice twice."
                        : "The extraction was interrupted and no attempts are left.";
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
