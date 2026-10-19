package com.actify.invoice.dashboard;

import com.actify.invoice.entity.GeminiCallAttempt;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.InvoiceVersion;
import com.actify.invoice.entity.InvoiceWorkflowEvent;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.queue.ExtractionWorker;
import com.actify.invoice.repository.InvoiceVersionRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.versioning.InvoiceSnapshot;
import com.actify.invoice.versioning.InvoiceSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The three role dashboards. Every number is a plain, documented count or ratio over workflow invoices
 * (legacy invoices are excluded and counted separately for the admin). Each metric carries its definition, so the
 * page can show WHAT a number means. There is NO "Gemini accuracy %": that needs an independently checked, labelled
 * sample, which does not exist yet - those metrics are reported as null with an explanation.
 *
 * Business days are Asia/Kolkata (BusinessPeriod). Gemini's daily quota is shown separately in Pacific time.
 */
@Service
public class DashboardService {

    static final int MIN_SAMPLES_FOR_MEDIAN = 20;
    private static final int MAX_SUBMISSIONS_ANALYSED = 500;

    private final CurrentUserService currentUser;
    private final InvoiceVersionRepository versionRepository;
    private final InvoiceSnapshotMapper snapshotMapper;
    private final GeminiQuotaGuard quotaGuard;
    private final ExtractionWorker worker;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public DashboardService(CurrentUserService currentUser, InvoiceVersionRepository versionRepository,
                            InvoiceSnapshotMapper snapshotMapper, GeminiQuotaGuard quotaGuard, ExtractionWorker worker,
                            Clock clock) {
        this.currentUser = currentUser;
        this.versionRepository = versionRepository;
        this.snapshotMapper = snapshotMapper;
        this.quotaGuard = quotaGuard;
        this.worker = worker;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- vendor

    @Transactional(readOnly = true)
    public Map<String, Object> vendor(int days) {
        AuthenticatedUser me = currentUser.require();
        BusinessPeriod period = BusinessPeriod.lastDays(clock, days);
        Map<String, Object> d = header(period);
        d.put("statusCounts", statusCounts("i.ownerUserId = :uid", me.getId()));
        d.put("receivedToday", count("select count(i) from Invoice i where i.ownerUserId = :uid and i.createdAt >= :from",
                me.getId(), BusinessPeriod.lastDays(clock, 1).start()));
        d.put("receivedInPeriod", count("select count(i) from Invoice i where i.ownerUserId = :uid and i.createdAt >= :from",
                me.getId(), period.start()));
        d.put("batches", count("select count(b) from UploadBatch b where b.ownerUserId = :uid", me.getId(), null));
        d.put("recentFailures", recentFailures(me.getId()));
        d.put("geminiUsage", geminiUsage(period, "i.ownerUserId = :uid", me.getId()));
        return d;
    }

    // ---------------------------------------------------------------- reviewer

    @Transactional(readOnly = true)
    public Map<String, Object> reviewer(int days) {
        AuthenticatedUser me = currentUser.require();
        BusinessPeriod period = BusinessPeriod.lastDays(clock, days);
        Map<String, Object> d = header(period);
        d.put("assignedStatusCounts", statusCounts("i.assignedReviewerId = :uid", me.getId()));
        d.put("oldestWaitingSince", oldestNeedsReviewSince(me.getId()));
        d.put("submittedInPeriod", eventsByActor(InvoiceStatus.PENDING_APPROVAL, me.getId(), period).size());
        d.put("correctionsSavedInPeriod", count("select count(v) from InvoiceVersion v where v.changedBy = :actor "
                + "and v.changeSource = com.actify.invoice.entity.VersionChangeSource.MANUAL_EDIT and v.createdAt >= :from",
                null, period.start(), "user:" + me.getId()));
        d.put("myCorrectionRate", correctionMetric(eventsByActor(InvoiceStatus.PENDING_APPROVAL, me.getId(), period)));
        return d;
    }

    // ---------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public Map<String, Object> admin(int days) {
        BusinessPeriod period = BusinessPeriod.lastDays(clock, days);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> d = header(period);
        d.put("statusCounts", statusCounts("i.ownerUserId is not null", null));
        d.put("legacyInvoices", count("select count(i) from Invoice i where i.ownerUserId is null", null, null));
        d.put("receivedToday", count("select count(i) from Invoice i where i.ownerUserId is not null and i.createdAt >= :from",
                null, BusinessPeriod.lastDays(clock, 1).start()));

        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("workerEnabled", worker.isEnabled());
        queue.put("waitingForRetryOrDailyReset", count("select count(j) from ExtractionJob j where "
                + "j.invoice.status = com.actify.invoice.entity.InvoiceStatus.QUEUED and j.nextAttemptAt > :from", null, now));
        queue.put("oldestQueuedSince", em.createQuery("select min(j.queuedAt) from ExtractionJob j where "
                + "j.invoice.status = com.actify.invoice.entity.InvoiceStatus.QUEUED", LocalDateTime.class).getSingleResult());
        queue.put("quota", quotaGuard.status());
        d.put("queue", queue);

        d.put("reviewerWorkload", reviewerWorkload());
        d.put("decisionsInPeriod", decisions(period));
        d.put("quality", quality(period));
        d.put("geminiUsage", geminiUsage(period, null, null));
        d.put("usageByVendor", usageByVendor(period));
        return d;
    }

    // ---------------------------------------------------------------- building blocks

    private Map<String, Object> header(BusinessPeriod period) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("timeZone", period.getTimeZone());
        d.put("periodFirstDay", period.getFirstDay());
        d.put("periodLastDay", period.getLastDay());
        return d;
    }

    private Map<InvoiceStatus, Long> statusCounts(String scope, Long userId) {
        TypedQuery<Object[]> q = em.createQuery("select i.status, count(i) from Invoice i where " + scope
                + " group by i.status", Object[].class);
        if (userId != null) {
            q.setParameter("uid", userId);
        }
        Map<InvoiceStatus, Long> counts = new EnumMap<>(InvoiceStatus.class);
        for (Object[] row : q.getResultList()) {
            counts.put((InvoiceStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private long count(String jpql, Long userId, LocalDateTime from) {
        return count(jpql, userId, from, null);
    }

    private long count(String jpql, Long userId, LocalDateTime from, String actor) {
        TypedQuery<Long> q = em.createQuery(jpql, Long.class);
        if (userId != null) {
            q.setParameter("uid", userId);
        }
        if (from != null) {
            q.setParameter("from", from);
        }
        if (actor != null) {
            q.setParameter("actor", actor);
        }
        return q.getSingleResult();
    }

    private List<Map<String, Object>> recentFailures(Long ownerId) {
        List<Object[]> rows = em.createQuery("select i.id, i.sourceFileName, i.failureReason, i.updatedAt from Invoice i "
                + "where i.ownerUserId = :uid and i.status = com.actify.invoice.entity.InvoiceStatus.FAILED order by i.id desc",
                Object[].class).setParameter("uid", ownerId).setMaxResults(10).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("invoiceId", row[0]);
            item.put("fileName", row[1]);
            item.put("reason", row[2]);
            item.put("at", row[3]);
            result.add(item);
        }
        return result;
    }

    private LocalDateTime oldestNeedsReviewSince(Long reviewerId) {
        List<LocalDateTime> since = em.createQuery("select max(e.createdAt) from InvoiceWorkflowEvent e, Invoice i "
                + "where e.invoiceId = i.id and i.assignedReviewerId = :uid "
                + "and i.status = com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW "
                + "and e.toStatus = com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW group by i.id", LocalDateTime.class)
                .setParameter("uid", reviewerId).getResultList();
        return since.isEmpty() ? null : Collections.min(since);
    }

    private List<InvoiceWorkflowEvent> eventsByActor(InvoiceStatus to, Long actorId, BusinessPeriod period) {
        return em.createQuery("select e from InvoiceWorkflowEvent e where e.toStatus = :to and e.actorUserId = :uid "
                + "and e.createdAt >= :from order by e.id desc", InvoiceWorkflowEvent.class)
                .setParameter("to", to).setParameter("uid", actorId).setParameter("from", period.start())
                .setMaxResults(MAX_SUBMISSIONS_ANALYSED).getResultList();
    }

    private List<Map<String, Object>> reviewerWorkload() {
        List<Object[]> rows = em.createQuery("select i.assignedReviewerId, u.displayName, i.status, count(i) "
                + "from Invoice i, AppUser u where u.id = i.assignedReviewerId "
                + "and i.status in (com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW, "
                + "com.actify.invoice.entity.InvoiceStatus.PENDING_APPROVAL) "
                + "group by i.assignedReviewerId, u.displayName, i.status order by i.assignedReviewerId", Object[].class)
                .getResultList();
        Map<Long, Map<String, Object>> byReviewer = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Map<String, Object> r = byReviewer.computeIfAbsent((Long) row[0], id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reviewerId", id);
                m.put("displayName", row[1]);
                m.put(InvoiceStatus.NEEDS_REVIEW.name(), 0L);
                m.put(InvoiceStatus.PENDING_APPROVAL.name(), 0L);
                return m;
            });
            r.put(((InvoiceStatus) row[2]).name(), row[3]);
        }
        Map<String, Object> unassigned = new LinkedHashMap<>();
        unassigned.put("reviewerId", null);
        unassigned.put("displayName", "(not assigned)");
        unassigned.put(InvoiceStatus.NEEDS_REVIEW.name(), count("select count(i) from Invoice i where i.ownerUserId is not null "
                + "and i.assignedReviewerId is null and i.status = com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW", null, null));
        List<Map<String, Object>> result = new ArrayList<>(byReviewer.values());
        result.add(unassigned);
        return result;
    }

    private Map<String, Object> decisions(BusinessPeriod period) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (InvoiceStatus to : new InvoiceStatus[]{InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.APPROVED, InvoiceStatus.REJECTED}) {
            d.put(to.name(), em.createQuery("select count(e) from InvoiceWorkflowEvent e where e.toStatus = :to "
                    + "and e.createdAt >= :from", Long.class).setParameter("to", to).setParameter("from", period.start())
                    .getSingleResult());
        }
        d.put("definition", "Number of status changes INTO each status during the period (one invoice can count more "
                + "than once, e.g. when it is reopened). APPROVED = a person approved a saved version, not 'Gemini was right'.");
        return d;
    }

    private Map<String, Object> quality(BusinessPeriod period) {
        // Cohort: invoices whose extraction finished in the period (PROCESSING -> NEEDS_REVIEW).
        List<Long> extracted = em.createQuery("select distinct e.invoiceId from InvoiceWorkflowEvent e where "
                + "e.fromStatus = com.actify.invoice.entity.InvoiceStatus.PROCESSING "
                + "and e.toStatus = com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW and e.createdAt >= :from", Long.class)
                .setParameter("from", period.start()).getResultList();
        Map<String, Long> reasonBreakdown = new TreeMap<>();
        Set<Long> withReasons = new HashSet<>();
        if (!extracted.isEmpty()) {
            for (Object[] row : em.createQuery("select r.invoice.id, r.code from InvoiceReviewReason r "
                    + "where r.invoice.id in :ids and r.versionNumber = 1", Object[].class)
                    .setParameter("ids", extracted).getResultList()) {
                withReasons.add((Long) row[0]);
                reasonBreakdown.merge(row[1].toString(), 1L, Long::sum);
            }
        }
        Map<String, Object> q = new LinkedHashMap<>();
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("extracted", extracted.size());
        pass.put("passedAllChecks", extracted.size() - withReasons.size());
        pass.put("rate", extracted.isEmpty() ? null : (double) (extracted.size() - withReasons.size()) / extracted.size());
        pass.put("definition", "Invoices extracted in the period with zero automatic review reasons / invoices extracted "
                + "in the period. Passing the checks does NOT prove the values are correct.");
        q.put("automaticChecksPassed", pass);

        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("counts", reasonBreakdown);
        reasons.put("definition", "Number of review reasons by code on the extraction (V1) of invoices extracted in the "
                + "period. One invoice can have several reasons.");
        q.put("reviewReasons", reasons);

        List<InvoiceWorkflowEvent> submissions = em.createQuery("select e from InvoiceWorkflowEvent e where "
                + "e.toStatus = com.actify.invoice.entity.InvoiceStatus.PENDING_APPROVAL and e.createdAt >= :from order by e.id desc",
                InvoiceWorkflowEvent.class).setParameter("from", period.start()).setMaxResults(MAX_SUBMISSIONS_ANALYSED)
                .getResultList();
        q.put("correctionRate", correctionMetric(submissions));

        Map<String, Object> accuracy = new LinkedHashMap<>();
        accuracy.put("observedFieldMatchRate", null);
        accuracy.put("reviewerAgreement", null);
        accuracy.put("definition", "Not shown: these need an independently verified, labelled sample (ground truth) and a "
                + "double-reviewed sample, which do not exist yet. Model 'confidence', check pass rates and edit rates are "
                + "NOT accuracy.");
        q.put("accuracy", accuracy);

        q.put("medianExtractionSeconds", medianExtractionSeconds(period));
        return q;
    }

    private Map<String, Object> correctionMetric(List<InvoiceWorkflowEvent> submissions) {
        CorrectionStats stats = new CorrectionStats();
        for (InvoiceWorkflowEvent submission : submissions) {
            if (submission.getVersionNumber() == null) {
                continue;
            }
            InvoiceVersion first = versionRepository.findByInvoice_IdAndVersionNumber(submission.getInvoiceId(), 1).orElse(null);
            InvoiceVersion submitted = versionRepository.findByInvoice_IdAndVersionNumber(submission.getInvoiceId(),
                    submission.getVersionNumber()).orElse(null);
            if (first == null || submitted == null) {
                continue;
            }
            InvoiceSnapshot a = snapshotMapper.snapshotFromJson(first.getSnapshotData());
            InvoiceSnapshot b = snapshotMapper.snapshotFromJson(submitted.getSnapshotData());
            stats.add(snapshotMapper.diff(a, b), a.getItems().size(), b.getItems().size());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("submissions", stats.getSubmissions());
        m.put("fieldsCompared", stats.getFieldsCompared());
        m.put("fieldsChanged", stats.getFieldsChanged());
        m.put("rate", stats.getRate());
        m.put("changesByField", stats.getChangesByField());
        m.put("definition", "Fields changed between the extraction (V1) and the submitted version / fields compared "
                + "(12 header fields + 5 per line item), over submissions in the period. Measures human editing, not accuracy.");
        return m;
    }

    private Map<String, Object> medianExtractionSeconds(BusinessPeriod period) {
        List<Long> durations = em.createQuery("select a.durationMs from GeminiCallAttempt a where a.outcome = :ok "
                + "and a.durationMs is not null and a.startedAt >= :from order by a.durationMs", Long.class)
                .setParameter("ok", GeminiCallAttempt.Outcome.SUCCESS).setParameter("from", period.start()).getResultList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", durations.size());
        m.put("value", durations.size() < MIN_SAMPLES_FOR_MEDIAN ? null : durations.get(durations.size() / 2) / 1000.0);
        m.put("definition", "Median duration of successful Gemini calls in the period; shown only with at least "
                + MIN_SAMPLES_FOR_MEDIAN + " calls.");
        return m;
    }

    /** Calls, outcomes and Gemini-REPORTED tokens per business day and model (estimates are never summed as usage). */
    private Map<String, Object> geminiUsage(BusinessPeriod period, String scope, Long userId) {
        TypedQuery<GeminiCallAttempt> q = em.createQuery("select a from GeminiCallAttempt a join a.invoice i where "
                + "a.startedAt >= :from" + (scope == null ? "" : " and " + scope) + " order by a.id", GeminiCallAttempt.class)
                .setParameter("from", period.start());
        if (userId != null) {
            q.setParameter("uid", userId);
        }
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        long calls = 0;
        long reportedTotalTokens = 0;
        long callsWithoutReportedTokens = 0;
        for (GeminiCallAttempt a : q.getResultList()) {
            LocalDate day = period.businessDayOf(a.getStartedAt());
            Map<String, Object> row = rows.computeIfAbsent(day + " " + a.getModelId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", day);
                m.put("model", a.getModelId());
                m.put("calls", 0L);
                m.put("errors", 0L);
                m.put("promptTokens", 0L);
                m.put("outputTokens", 0L);
                m.put("totalTokens", 0L);
                return m;
            });
            row.put("calls", (Long) row.get("calls") + 1);
            if (a.getOutcome() != GeminiCallAttempt.Outcome.SUCCESS && a.getOutcome() != GeminiCallAttempt.Outcome.IN_FLIGHT) {
                row.put("errors", (Long) row.get("errors") + 1);
            }
            row.put("promptTokens", (Long) row.get("promptTokens") + nz(a.getPromptTokenCount()));
            row.put("outputTokens", (Long) row.get("outputTokens") + nz(a.getCandidatesTokenCount()));
            row.put("totalTokens", (Long) row.get("totalTokens") + nz(a.getTotalTokenCount()));
            calls++;
            reportedTotalTokens += nz(a.getTotalTokenCount());
            if (a.getTotalTokenCount() == null) {
                callsWithoutReportedTokens++;
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("calls", calls);
        usage.put("reportedTotalTokens", reportedTotalTokens);
        usage.put("callsWithoutReportedTokens", callsWithoutReportedTokens);
        usage.put("byDayAndModel", new ArrayList<>(rows.values()));
        usage.put("definition", "Every HTTP call to Gemini (worker and legacy endpoint). Tokens are only what Gemini "
                + "reported in usageMetadata; calls without a report (e.g. network errors) add 0 and are counted separately.");
        return usage;
    }

    private List<Map<String, Object>> usageByVendor(BusinessPeriod period) {
        List<Object[]> rows = em.createQuery("select i.ownerUserId, count(a), sum(a.totalTokenCount) from GeminiCallAttempt a "
                + "join a.invoice i where a.startedAt >= :from group by i.ownerUserId order by count(a) desc", Object[].class)
                .setParameter("from", period.start()).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vendorUserId", row[0]); // null = legacy single-file uploads by an admin
            m.put("calls", row[1]);
            m.put("reportedTotalTokens", row[2] == null ? 0L : row[2]);
            result.add(m);
        }
        return result;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

}
