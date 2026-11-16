package com.actify.invoice.review;

import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.queue.WorkerStatusService;
import com.actify.invoice.quota.QuotaStatus;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.UserNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The admin's intake board (GET /api/admin/intake): every batch invoice that is still being prepared for review,
 * visible from the moment the vendor's upload is accepted - also while it waits for extraction.
 *
 *  - needsAssignment: QUEUED / PROCESSING / NEEDS_REVIEW with NO reviewer, oldest first;
 *  - assigned:        the same statuses WITH a reviewer (track / reassign here);
 *  - every row says which stage it is in, its extractionState (waiting, waiting for quota, missing free-tier consent,
 *    extracting, ready for assignment / review) and, when it cannot move, why.
 * FAILED invoices keep their own admin section; the board only counts them (counts.extractionFailed).
 *
 * Assignment is allowed before extraction (ReviewAssignmentService.ASSIGNABLE); the reviewer can only EDIT once the
 * invoice is NEEDS_REVIEW. PENDING_APPROVAL / APPROVED / REJECTED / FAILED have their own admin sections.
 */
@Service
public class IntakeBoardService {

    /** Statuses shown on the board = the statuses in which an assignment may still change. */
    static final Set<InvoiceStatus> BOARD_STATUSES =
            EnumSet.of(InvoiceStatus.QUEUED, InvoiceStatus.PROCESSING, InvoiceStatus.NEEDS_REVIEW);
    static final int MAX_ROWS = 500;

    /** Row extractionState values (GET /api/admin/intake). */
    static final String WAITING_FOR_EXTRACTION = "WAITING_FOR_EXTRACTION";
    static final String WAITING_FOR_QUOTA = "WAITING_FOR_QUOTA";
    static final String MISSING_FREE_TIER_CONSENT = "MISSING_FREE_TIER_CONSENT";
    static final String EXTRACTING = "EXTRACTING";
    static final String READY_FOR_REVIEWER_ASSIGNMENT = "READY_FOR_REVIEWER_ASSIGNMENT";
    static final String READY_FOR_REVIEW = "READY_FOR_REVIEW";

    private final InvoiceRepository invoiceRepository;
    private final ExtractionJobRepository jobRepository;
    private final WorkerStatusService workerStatus;
    private final UserNames userNames;
    private final Clock clock;

    public IntakeBoardService(InvoiceRepository invoiceRepository, ExtractionJobRepository jobRepository,
                              WorkerStatusService workerStatus, UserNames userNames, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.jobRepository = jobRepository;
        this.workerStatus = workerStatus;
        this.userNames = userNames;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> board() {
        QuotaStatus quota = workerStatus.quota();
        boolean running = workerStatus.isWorkerRunning(quota);
        String idleReason = workerStatus.idleReason(quota);
        boolean quotaWait = running && workerStatus.resumeAt(quota) != null;

        List<Invoice> unassigned = load(false);
        List<Invoice> assigned = load(true);

        List<Long> ids = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        for (List<Invoice> list : java.util.Arrays.asList(unassigned, assigned)) {
            for (Invoice i : list) {
                ids.add(i.getId());
                userIds.add(i.getOwnerUserId());
                userIds.add(i.getAssignedReviewerId());
            }
        }
        Map<Long, ExtractionJob> jobs = new HashMap<>();
        for (ExtractionJob job : jobRepository.findAllById(ids)) {
            jobs.put(job.getInvoiceId(), job);
        }
        Map<Long, String> names = userNames.namesById(userIds);
        LocalDateTime now = LocalDateTime.now(clock);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("enabled", workerStatus.isWorkerEnabled());
        worker.put("running", running);
        worker.put("idleReason", running ? idleReason : idleReason == null ? "The extraction worker is not running." : idleReason);
        worker.put("resumeAt", workerStatus.resumeAt(quota));
        // DEPRECATED, always []: the invoice-id allowlist was removed; kept for React screens built against the earlier
        // response so they do not break on a missing array.
        worker.put("onlyInvoiceIds", Collections.emptyList());
        worker.put("onlyInvoiceIdsProblems", Collections.emptyList());
        result.put("worker", worker);

        List<Map<String, Object>> needsAssignment = rows(unassigned, jobs, names, running, idleReason, quotaWait, now);
        List<Map<String, Object>> assignedRows = rows(assigned, jobs, names, running, idleReason, quotaWait, now);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("needsAssignment", needsAssignment.size());
        counts.put("needsAssignmentWaitingForExtraction", countStage(needsAssignment, false));
        counts.put("assigned", assignedRows.size());
        counts.put("assignedWaitingForExtraction", countStage(assignedRows, false));
        counts.put("assignedReadyForReview", countStage(assignedRows, true));
        counts.put("missingFreeTierConsent", countState(needsAssignment, assignedRows, MISSING_FREE_TIER_CONSENT));
        counts.put("waitingForQuota", countState(needsAssignment, assignedRows, WAITING_FOR_QUOTA));
        counts.put("extracting", countState(needsAssignment, assignedRows, EXTRACTING));
        counts.put("extractionFailed", invoiceRepository.count((root, query, cb) -> cb.and(
                cb.isNotNull(root.get("ownerUserId")), cb.equal(root.get("status"), InvoiceStatus.FAILED))));
        result.put("counts", counts);
        result.put("needsAssignment", needsAssignment);
        result.put("assigned", assignedRows);
        result.put("definition", "Batch invoices in QUEUED, PROCESSING or NEEDS_REVIEW, split by whether a reviewer is "
                + "assigned; oldest upload first; at most " + MAX_ROWS + " rows per list. A reviewer can edit only when "
                + "the stage is READY_FOR_REVIEW (status NEEDS_REVIEW). FAILED invoices are listed in their own section "
                + "(counts.extractionFailed).");
        return result;
    }

    private List<Invoice> load(boolean withReviewer) {
        Specification<Invoice> spec = (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("ownerUserId")),
                root.get("status").in(BOARD_STATUSES),
                withReviewer ? cb.isNotNull(root.get("assignedReviewerId")) : cb.isNull(root.get("assignedReviewerId")));
        return invoiceRepository.findAll(spec, PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.ASC, "id"))).getContent();
    }

    private static List<Map<String, Object>> rows(List<Invoice> invoices, Map<Long, ExtractionJob> jobs, Map<Long, String> names,
                                                  boolean running, String idleReason, boolean quotaWait, LocalDateTime now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice i : invoices) {
            ExtractionJob job = jobs.get(i.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("invoiceId", i.getId());
            r.put("fileName", i.getSourceFileName());
            r.put("batchId", i.getBatchId());
            r.put("vendorName", UserNames.nameOrUnknown(names, i.getOwnerUserId()));
            r.put("categoryCode", i.getCategoryCode());
            r.put("status", i.getStatus());
            r.put("stage", stage(i.getStatus()));
            r.put("extractionState", extractionState(i, job, running, quotaWait, now));
            r.put("freeTierProcessingConsent", hasFreeTierConsent(i));
            r.put("uploadedAt", i.getCreatedAt());
            r.put("assignedReviewerId", i.getAssignedReviewerId());
            r.put("assignedReviewerName", i.getAssignedReviewerId() == null ? null
                    : names.getOrDefault(i.getAssignedReviewerId(), UserNames.UNKNOWN_USER));
            r.put("extractionAttempts", job == null ? null : job.getAttemptCount());
            r.put("nextAttemptAt", job == null || i.getStatus() != InvoiceStatus.QUEUED ? null : job.getNextAttemptAt());
            r.put("blockedReason", i.getStatus() == InvoiceStatus.QUEUED && !hasFreeTierConsent(i)
                    ? NO_FREE_TIER_CONSENT : blockedReason(i, job, running, idleReason, now));
            rows.add(r);
        }
        return rows;
    }

    static String stage(InvoiceStatus status) {
        switch (status) {
            case QUEUED:
                return "WAITING_FOR_EXTRACTION";
            case PROCESSING:
                return "EXTRACTING";
            default:
                return "READY_FOR_REVIEW";
        }
    }

    /**
     * Finer than stage: QUEUED splits into missing consent (never sent), waiting for quota (a known quota wait, or a
     * 429 / daily-quota deferral on this job) and plain waiting; NEEDS_REVIEW splits by whether a reviewer is assigned.
     */
    static String extractionState(Invoice invoice, ExtractionJob job, boolean running, boolean quotaWait, LocalDateTime now) {
        switch (invoice.getStatus()) {
            case PROCESSING:
                return EXTRACTING;
            case NEEDS_REVIEW:
                return invoice.getAssignedReviewerId() == null ? READY_FOR_REVIEWER_ASSIGNMENT : READY_FOR_REVIEW;
            default:
                if (!hasFreeTierConsent(invoice)) {
                    return MISSING_FREE_TIER_CONSENT;
                }
                boolean deferredByQuota = job != null && job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(now)
                        && job.getLastErrorCode() != null && job.getLastErrorCode().startsWith("HTTP_429");
                return running && (quotaWait || deferredByQuota) ? WAITING_FOR_QUOTA : WAITING_FOR_EXTRACTION;
        }
    }

    static final String NO_FREE_TIER_CONSENT = "Not sent to Gemini: this invoice was uploaded without free-tier "
            + "processing consent (or before consent existed). It stays queued and is never sent; the vendor can upload "
            + "it again in a new batch with consent if the document is non-confidential.";

    private static boolean hasFreeTierConsent(Invoice invoice) {
        return invoice.getBatch() != null && invoice.getBatch().isFreeTierConsent();
    }

    /** null = nothing blocks it. Plain sentences; never a stack trace or a file path. */
    static String blockedReason(Invoice invoice, ExtractionJob job, boolean running, String idleReason, LocalDateTime now) {
        if (invoice.getStatus() == InvoiceStatus.NEEDS_REVIEW) {
            return invoice.getAssignedReviewerId() == null ? "Extraction finished; waiting for a reviewer to be assigned." : null;
        }
        if (invoice.getStatus() == InvoiceStatus.PROCESSING) {
            return null; // being extracted right now
        }
        if (!running) {
            return "Waiting for extraction: " + (idleReason == null ? "the extraction worker is not running." : idleReason);
        }
        if (job != null && job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(now)) {
            return "Next extraction attempt at " + job.getNextAttemptAt()
                    + (job.getLastErrorCode() == null ? "" : " (last attempt: " + job.getLastErrorCode() + ")") + ".";
        }
        return idleReason == null ? "Waiting in the extraction queue." : "Waiting in the extraction queue: " + idleReason;
    }

    private static long countStage(List<Map<String, Object>> rows, boolean ready) {
        long n = 0;
        for (Map<String, Object> r : rows) {
            if ("READY_FOR_REVIEW".equals(r.get("stage")) == ready) {
                n++;
            }
        }
        return n;
    }

    private static long countState(List<Map<String, Object>> a, List<Map<String, Object>> b, String state) {
        long n = 0;
        for (List<Map<String, Object>> rows : java.util.Arrays.asList(a, b)) {
            for (Map<String, Object> r : rows) {
                if (state.equals(r.get("extractionState"))) {
                    n++;
                }
            }
        }
        return n;
    }
}
