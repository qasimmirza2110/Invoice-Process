package com.actify.invoice.quota;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.GeminiCallAttempt;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.repository.GeminiCallAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * ONE shared pacer for every Gemini call of this application (the queue worker is the only caller).
 *
 * Before a call, inside the worker's short claim transaction:
 *   1. take a PostgreSQL advisory lock, so two workers cannot both decide "there is quota left" at the same moment;
 *   2. check() - requests since the last midnight Pacific time (RPD, Google's reset time), a 60 s cool-down after a
 *      per-minute 429, requests in the last 60 s (RPM) with even spacing, and input tokens in the last 60 s (TPM)
 *      PLUS a reservation for the PDF about to be sent - each against limit x safety margin;
 *   3. recordStart() - insert the attempt row (IN_FLIGHT, with the reservation) and commit. Counting at send time
 *      means a crash still counts. The rows live in PostgreSQL: a restart never resets the counters.
 * After the call: complete() stores the outcome and the prompt / output / total token counts Gemini reported, which
 * then replace the reservation in the TPM sum.
 *
 * Honest limits of this design: it only sees THIS app's calls (Google counts per project, across all keys of that
 * project), the minute window is a rolling 60 s (Google's exact window is not documented), and tokens are an estimate
 * until Gemini reports them. A 429 from Gemini therefore always wins: it defers the queue (FailureClassifier,
 * ExtractionWorker) and never causes a tight retry loop.
 */
@Service
public class GeminiQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(GeminiQuotaGuard.class);

    /** Google resets the requests-per-day quota at midnight Pacific time (DST handled by the zone rules). */
    public static final ZoneId QUOTA_RESET_ZONE = ZoneId.of("America/Los_Angeles");
    static final long ADVISORY_LOCK_KEY = 80880008L;
    static final int IN_FLIGHT_STALE_MINUTES = 10;
    /** After a per-minute 429 from Gemini no call starts for this long (one full rate-limit window). */
    public static final long RATE_LIMIT_COOLDOWN_SECONDS = 60;
    /** How often a refused claim is re-evaluated while the minute window drains (no Gemini call is made meanwhile). */
    static final long MINUTE_WINDOW_RECHECK_SECONDS = 5;
    private static final int ESTIMATE_SAMPLE_SIZE = 20;

    private final QuotaSettings settings;
    private final GeminiCallAttemptRepository attempts;
    private final GeminiConfig geminiConfig;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public GeminiQuotaGuard(QuotaSettings settings, GeminiCallAttemptRepository attempts, GeminiConfig geminiConfig,
                            Clock clock) {
        this.settings = settings;
        this.attempts = attempts;
        this.geminiConfig = geminiConfig;
        this.clock = clock;
    }

    /** The answer of check(): either "go ahead" or "not now, and why / until when". */
    public static final class Check {
        private final boolean allowed;
        private final String reason;
        private final LocalDateTime notBefore;
        private final int estimatedInputTokens;

        private Check(boolean allowed, String reason, LocalDateTime notBefore, int estimatedInputTokens) {
            this.allowed = allowed;
            this.reason = reason;
            this.notBefore = notBefore;
            this.estimatedInputTokens = estimatedInputTokens;
        }

        public static Check refused(String reason, LocalDateTime notBefore, int estimatedInputTokens) {
            return new Check(false, reason, notBefore, estimatedInputTokens);
        }

        public static Check allowed(int estimatedInputTokens) {
            return new Check(true, null, null, estimatedInputTokens);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
        public LocalDateTime getNotBefore() { return notBefore; }
        public int getEstimatedInputTokens() { return estimatedInputTokens; }
    }

    /**
     * Must run inside the caller's transaction: the advisory lock is held until that transaction ends, so
     * check() and the following recordStart() are one atomic decision. Without configured limits nothing is allowed.
     *
     * @param documentSizeBytes size of the PDF about to be sent (scales the token reservation), or null when unknown
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Check check(Long documentSizeBytes) {
        lock();
        LocalDateTime now = LocalDateTime.now(clock);
        int estimate = estimateInputTokens(documentSizeBytes);
        if (!settings.isConfigured()) {
            return new Check(false, "Gemini limits are not configured: " + settings.getProblem(), null, estimate);
        }

        LocalDateTime pausedUntil = dailyPauseUntil(now);
        if (pausedUntil != null) {
            return new Check(false, "Daily Gemini quota (RPD) reached; paused until the reset (midnight Pacific time).",
                    pausedUntil, estimate);
        }
        // Google said "too many requests" (a per-minute 429): nobody calls again for a full minute, so the next queued
        // invoice does not run straight into the same 429 (Google's limit counts the whole project, not only this app).
        LocalDateTime cooldownUntil = rateLimitCooldownUntil(now);
        if (cooldownUntil != null) {
            return new Check(false, "Gemini answered 429 (rate limit); cooling down until " + cooldownUntil + ".",
                    cooldownUntil, estimate);
        }
        LocalDateTime minuteAgo = now.minusSeconds(60);
        long requestsLastMinute = attempts.countStartedSince(model(), minuteAgo);
        if (requestsLastMinute >= settings.budget(settings.getRpm())) {
            return new Check(false, "Requests-per-minute (RPM) budget used; waiting.",
                    now.plusSeconds(MINUTE_WINDOW_RECHECK_SECONDS), estimate);
        }
        // Even pacing: never start two calls closer together than 60 s / budget (e.g. 5 s for 12 calls a minute).
        LocalDateTime lastStart = settings.minIntervalMillis() > 0 ? attempts.lastStartedAt(model()) : null;
        if (lastStart != null) {
            LocalDateTime earliest = lastStart.plusNanos(settings.minIntervalMillis() * 1_000_000L);
            if (now.isBefore(earliest)) {
                return new Check(false, "Pacing calls evenly over the minute; next call at " + earliest + ".", earliest, estimate);
            }
        }
        long tokensLastMinute = attempts.sumInputTokensSince(model(), minuteAgo);
        // A single invoice bigger than the whole minute budget must still be allowed once the minute is empty.
        if (tokensLastMinute > 0 && tokensLastMinute + estimate > settings.budget(settings.getTpm())) {
            return new Check(false, "Input-tokens-per-minute (TPM) budget used (this PDF needs about " + estimate
                    + " tokens); waiting.", now.plusSeconds(MINUTE_WINDOW_RECHECK_SECONDS), estimate);
        }
        return new Check(true, null, null, estimate);
    }

    /** Inserts the IN_FLIGHT attempt row. Call right after an allowed check(), in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GeminiCallAttempt recordStart(Invoice invoice, GeminiCallAttempt.Caller caller, int estimatedInputTokens) {
        int attemptNumber = attempts.maxAttemptNumber(invoice.getId()) + 1;
        return attempts.save(new GeminiCallAttempt(invoice, attemptNumber, caller, geminiConfig.getModel(),
                LocalDateTime.now(clock), estimatedInputTokens));
    }

    /** Stores the outcome of a finished call. A null id (nothing was recorded) is ignored. */
    @Transactional
    public void complete(Long attemptId, GeminiCallAttempt.Outcome outcome, Integer httpStatus, String errorCode,
                         TokenUsage usage, long durationMs) {
        if (attemptId == null) {
            return;
        }
        attempts.findById(attemptId).ifPresent(a ->
                a.complete(outcome, httpStatus, errorCode, usage, durationMs, LocalDateTime.now(clock)));
    }

    /** Calls that never reported back (process died) become UNKNOWN; they keep counting against the quota. */
    @Transactional
    public int recoverInFlight() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusMinutes(IN_FLIGHT_STALE_MINUTES);
        List<GeminiCallAttempt> stale = attempts.findByOutcomeStartedBefore(GeminiCallAttempt.Outcome.IN_FLIGHT, cutoff);
        for (GeminiCallAttempt attempt : stale) {
            attempt.markUnknown(now);
        }
        if (!stale.isEmpty()) {
            log.warn("{} Gemini call(s) never reported back; marked UNKNOWN", stale.size());
        }
        return stale.size();
    }

    /** Next midnight Pacific time, as a local (server time zone) date-time - the format every column uses. */
    public LocalDateTime nextDailyReset() {
        ZonedDateTime nowPacific = ZonedDateTime.now(clock.withZone(QUOTA_RESET_ZONE));
        ZonedDateTime next = nowPacific.toLocalDate().plusDays(1).atStartOfDay(QUOTA_RESET_ZONE);
        return LocalDateTime.ofInstant(next.toInstant(), clock.getZone());
    }

    /** Last midnight Pacific time, as a local (server time zone) date-time. */
    public LocalDateTime lastDailyReset() {
        ZonedDateTime nowPacific = ZonedDateTime.now(clock.withZone(QUOTA_RESET_ZONE));
        return LocalDateTime.ofInstant(nowPacific.toLocalDate().atStartOfDay(QUOTA_RESET_ZONE).toInstant(), clock.getZone());
    }

    /** For the admin dashboard (read-only, no lock). Counts only - never invoice content or a secret. */
    @Transactional(readOnly = true)
    public QuotaStatus status() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime minuteAgo = now.minusSeconds(60);
        LocalDateTime since = lastDailyReset();
        boolean configured = settings.isConfigured();
        long requestsLastMinute = attempts.countStartedSince(model(), minuteAgo);
        long tokensLastMinute = attempts.sumInputTokensSince(model(), minuteAgo);
        long requestsToday = attempts.countStartedSince(model(), since);
        int estimate = estimateInputTokens(null);
        LocalDateTime pausedUntil = configured ? dailyPauseUntil(now) : null;
        QuotaStatus status = new QuotaStatus(configured, settings.getProblem(), geminiConfig.getModel(),
                settings.getRpm(), settings.getTpm(), settings.getRpd(), settings.getSafetyMargin(),
                requestsLastMinute, tokensLastMinute, requestsToday, pausedUntil, nextDailyReset(), estimate);
        status.setUsageSinceDailyReset(attempts.sumInputTokensSince(model(), since),
                attempts.sumOutputTokensSince(model(), since), attempts.sumTotalTokensSince(model(), since));
        if (configured) {
            status.setExhaustion(requestsLastMinute >= settings.budget(settings.getRpm()),
                    tokensLastMinute > 0 && tokensLastMinute + estimate > settings.budget(settings.getTpm()),
                    pausedUntil != null, rateLimitCooldownUntil(now),
                    settings.budget(settings.getRpm()), settings.budget(settings.getTpm()),
                    settings.budget(settings.getRpd()));
        }
        return status;
    }

    private LocalDateTime dailyPauseUntil(LocalDateTime now) {
        LocalDateTime since = lastDailyReset();
        boolean googleSaidDailyLimit = attempts.countOutcomeSince(model(), since, GeminiCallAttempt.Outcome.DAILY_QUOTA) > 0;
        boolean localBudgetUsed = attempts.countStartedSince(model(), since) >= settings.budget(settings.getRpd());
        return googleSaidDailyLimit || localBudgetUsed ? nextDailyReset() : null;
    }

    private LocalDateTime rateLimitCooldownUntil(LocalDateTime now) {
        LocalDateTime last429 = attempts.lastFinishedWithStatus(model(), 429, GeminiCallAttempt.Outcome.RETRYABLE_ERROR);
        if (last429 == null) {
            return null;
        }
        LocalDateTime resume = last429.plusSeconds(RATE_LIMIT_COOLDOWN_SECONDS);
        return now.isBefore(resume) ? resume : null;
    }

    /**
     * The input-token reservation for the next call. Gemini's reported promptTokenCount covers the PDF AND the extraction
     * prompt, so the history already includes both. Before any history exists: the configured initial estimate
     * (GEMINI_INITIAL_TOKEN_ESTIMATE). With history: the average of the last 20 reported counts, raised - never lowered -
     * to the tokens-per-byte rate of those calls times this PDF's size, so a larger PDF reserves more than a small one.
     */
    int estimateInputTokens(Long documentSizeBytes) {
        List<Object[]> recent = attempts.recentPromptTokensWithDocumentSize(model(), GeminiCallAttempt.Outcome.SUCCESS,
                PageRequest.of(0, ESTIMATE_SAMPLE_SIZE));
        if (recent.isEmpty()) {
            Integer initial = settings.getInitialTokenEstimate();
            return initial == null ? 0 : initial;
        }
        long tokens = 0;
        long bytes = 0;
        for (Object[] row : recent) {
            tokens += ((Number) row[0]).longValue();
            bytes += row[1] == null ? 0 : ((Number) row[1]).longValue();
        }
        long average = tokens / recent.size();
        long estimate = average;
        if (documentSizeBytes != null && documentSizeBytes > 0 && bytes > 0) {
            long scaled = (long) Math.ceil((double) tokens * documentSizeBytes / bytes);
            estimate = Math.max(average, scaled);
        }
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }

    private String model() {
        return geminiConfig.getModel();
    }

    // Transaction-scoped: released automatically at commit/rollback.
    private void lock() {
        entityManager.createNativeQuery("select 1 from (select pg_advisory_xact_lock(:key)) as quota_lock")
                .setParameter("key", ADVISORY_LOCK_KEY)
                .getSingleResult();
    }
}
