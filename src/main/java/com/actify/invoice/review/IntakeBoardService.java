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
 *  - every row says which stage it is in and, when it cannot move, why (worker off, quota pause, retry...).
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
        Set<Long> onlyIds = workerStatus.onlyInvoiceIds();
        worker.put("onlyInvoiceIds", onlyIds);
        worker.put("onlyInvoiceIdsProblems", allowlistProblems(onlyIds, invoiceRepository.findAllById(onlyIds)));
        result.put("worker", worker);

        List<Map<String, Object>> needsAssignment = rows(unassigned, jobs, names, running, idleReason, onlyIds, now);
        List<Map<String, Object>> assignedRows = rows(assigned, jobs, names, running, idleReason, onlyIds, now);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("needsAssignment", needsAssignment.size());
        counts.put("needsAssignmentWaitingForExtraction", countStage(needsAssignment, false));
        counts.put("assigned", assignedRows.size());
        counts.put("assignedWaitingForExtraction", countStage(assignedRows, false));
        counts.put("assignedReadyForReview", countStage(assignedRows, true));
        result.put("counts", counts);
        result.put("needsAssignment", needsAssignment);
        result.put("assigned", assignedRows);
        result.put("definition", "Batch invoices in QUEUED, PROCESSING or NEEDS_REVIEW, split by whether a reviewer is "
                + "assigned; oldest upload first; at most " + MAX_ROWS + " rows per list. A reviewer can edit only when "
                + "the stage is READY_FOR_REVIEW (status NEEDS_REVIEW).");
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
                                                  boolean running, String idleReason, Set<Long> onlyIds,
                                                  LocalDateTime now) {
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
            r.put("uploadedAt", i.getCreatedAt());
            r.put("assignedReviewerId", i.getAssignedReviewerId());
            r.put("assignedReviewerName", i.getAssignedReviewerId() == null ? null
                    : names.getOrDefault(i.getAssignedReviewerId(), UserNames.UNKNOWN_USER));
            r.put("extractionAttempts", job == null ? null : job.getAttemptCount());
            r.put("blockedReason", blockedReason(i, job, running, idleReason, onlyIds, now));
            rows.add(r);
        }
        return rows;
    }

    /**
     * A limited test run (INVOICE_WORKER_ONLY_INVOICE_IDS) only ever claims QUEUED batch invoices from its list. Ids that
     * do not exist, are legacy rows or are no longer QUEUED are simply never extracted - say so instead of staying quiet.
     */
    static List<String> allowlistProblems(Set<Long> onlyIds, List<Invoice> found) {
        Map<Long, Invoice> byId = new HashMap<>();
        for (Invoice invoice : found) {
            byId.put(invoice.getId(), invoice);
        }
        List<String> problems = new ArrayList<>();
        for (Long id : onlyIds) {
            Invoice invoice = byId.get(id);
            if (invoice == null) {
                problems.add("Invoice " + id + " does not exist, so the worker has nothing to extract for it.");
            } else if (invoice.getOwnerUserId() == null) {
                problems.add("Invoice " + id + " is a legacy invoice without an extraction job; it is never extracted by the worker.");
            } else if (invoice.getStatus() != InvoiceStatus.QUEUED && invoice.getStatus() != InvoiceStatus.PROCESSING) {
                problems.add("Invoice " + id + " is " + invoice.getStatus() + ", not QUEUED; it will not be extracted again.");
            }
        }
        return problems;
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

    /** null = nothing blocks it. Plain sentences; never a stack trace or a file path. */
    static String blockedReason(Invoice invoice, ExtractionJob job, boolean running, String idleReason, LocalDateTime now) {
        return blockedReason(invoice, job, running, idleReason, Collections.<Long>emptySet(), now);
    }

    static String blockedReason(Invoice invoice, ExtractionJob job, boolean running, String idleReason, Set<Long> onlyIds,
                                LocalDateTime now) {
        if (invoice.getStatus() == InvoiceStatus.NEEDS_REVIEW) {
            return invoice.getAssignedReviewerId() == null ? "Extraction finished; waiting for a reviewer to be assigned." : null;
        }
        if (invoice.getStatus() == InvoiceStatus.PROCESSING) {
            return null; // being extracted right now
        }
        if (!running) {
            return "Waiting for extraction: " + (idleReason == null ? "the extraction worker is not running." : idleReason);
        }
        if (!onlyIds.isEmpty() && !onlyIds.contains(invoice.getId())) {
            return "Waiting for extraction: this run's worker is limited to invoice(s) " + onlyIds
                    + " (INVOICE_WORKER_ONLY_INVOICE_IDS), so this invoice stays queued.";
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
}
