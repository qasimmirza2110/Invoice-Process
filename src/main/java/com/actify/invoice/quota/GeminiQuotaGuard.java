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
 * ONE shared pacer for every Gemini call of this application (the queue worker and the legacy /extract endpoint).
 *
 * Before a call, inside the caller's short transaction:
 *   1. take a PostgreSQL advisory lock, so two callers cannot both decide "there is quota left" at the same moment;
 *   2. check() - requests in the last 60 s (RPM), input tokens in the last 60 s (TPM) and requests since the last
 *      midnight Pacific time (RPD, Google's reset time), each against limit x safety margin;
 *   3. recordStart() - insert the attempt row (IN_FLIGHT) and commit. Counting at send time means a crash still counts.
 * After the call: complete() stores the outcome and the token counts Gemini reported.
 *
 * Honest limits of this design: it only sees THIS app's calls (Google counts per project), the minute window is a
 * rolling 60 s (Google's exact window is not documented), and tokens are an estimate until Gemini reports them.
 * A 429 from Gemini therefore always wins: it is retried or pauses the worker (FailureClassifier).
 */
@Service
public class GeminiQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(GeminiQuotaGuard.class);

    /** Google resets the requests-per-day quota at midnight Pacific time. */
    public static final ZoneId QUOTA_RESET_ZONE = ZoneId.of("America/Los_Angeles");
    static final long ADVISORY_LOCK_KEY = 80880008L;
    static final int IN_FLIGHT_STALE_MINUTES = 10;
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
     * check() and the following recordStart() are one atomic decision.
     *
     * @param enforceWhenNotConfigured true for the worker (no limits configured = no calls at all);
     *                                 false for the legacy endpoint, which worked before limits existed and still does
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Check check(boolean enforceWhenNotConfigured) {
        lock();
        LocalDateTime now = LocalDateTime.now(clock);
        int estimate = estimateInputTokens();
        if (!settings.isConfigured()) {
            return enforceWhenNotConfigured
                    ? new Check(false, "Gemini limits are not configured: " + settings.getProblem(), null, estimate)
                    : new Check(true, null, null, estimate);
        }

        LocalDateTime pausedUntil = dailyPauseUntil(now);
        if (pausedUntil != null) {
            return new Check(false, "Daily Gemini quota reached; paused until the reset (midnight Pacific time).",
                    pausedUntil, estimate);
        }
        LocalDateTime minuteAgo = now.minusSeconds(60);
        long requestsLastMinute = attempts.countStartedSince(model(), minuteAgo);
        if (requestsLastMinute >= settings.budget(settings.getRpm())) {
            return new Check(false, "Requests-per-minute budget used; waiting.", now.plusSeconds(5), estimate);
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
            return new Check(false, "Input-tokens-per-minute budget used; waiting.", now.plusSeconds(5), estimate);
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
        List<GeminiCallAttempt> stale = attempts.findByOutcomeStartedBefore(
                GeminiCallAttempt.Outcome.IN_FLIGHT, now.minusMinutes(IN_FLIGHT_STALE_MINUTES));
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

    /** For the admin dashboard (read-only, no lock). */
    @Transactional(readOnly = true)
    public QuotaStatus status() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime minuteAgo = now.minusSeconds(60);
        return new QuotaStatus(settings.isConfigured(), settings.getProblem(), geminiConfig.getModel(),
                settings.getRpm(), settings.getTpm(), settings.getRpd(), settings.getSafetyMargin(),
                attempts.countStartedSince(model(), minuteAgo), attempts.sumInputTokensSince(model(), minuteAgo),
                attempts.countStartedSince(model(), lastDailyReset()), settings.isConfigured() ? dailyPauseUntil(now) : null,
                nextDailyReset(), estimateInputTokens());
    }

    private LocalDateTime dailyPauseUntil(LocalDateTime now) {
        LocalDateTime since = lastDailyReset();
        boolean googleSaidDailyLimit = attempts.countOutcomeSince(model(), since, GeminiCallAttempt.Outcome.DAILY_QUOTA) > 0;
        boolean localBudgetUsed = attempts.countStartedSince(model(), since) >= settings.budget(settings.getRpd());
        return googleSaidDailyLimit || localBudgetUsed ? nextDailyReset() : null;
    }

    /**
     * Average of the last 20 input-token counts Gemini reported; before any history exists, the configured
     * initial estimate. (No PDF page counting: that would need a new PDF library.)
     */
    int estimateInputTokens() {
        List<Long> recent = attempts.recentPromptTokenCounts(model(), GeminiCallAttempt.Outcome.SUCCESS,
                PageRequest.of(0, ESTIMATE_SAMPLE_SIZE));
        if (recent.isEmpty()) {
            Integer initial = settings.getInitialTokenEstimate();
            return initial == null ? 0 : initial;
        }
        long sum = 0;
        for (Long count : recent) {
            sum += count;
        }
        return (int) Math.min(Integer.MAX_VALUE, sum / recent.size());
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
