package com.actify.invoice.quota;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.entity.GeminiCallAttempt.Outcome;
import com.actify.invoice.repository.GeminiCallAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GeminiQuotaGuard.check() with a fixed clock and a mocked attempt ledger (no database, no Gemini): each of RPM, TPM and
 * RPD stops the next call, a per-minute 429 cools the whole queue down for a minute, and the daily reset lets
 * processing resume. Limits as configured on the live service: 5 RPM / 50,000 TPM / 20 RPD x 0.8.
 * The advisory lock and the real SQL are covered by QuotaPgIntegrationTest.
 */
class GeminiQuotaGuardLimitsTest {

    private static final String MODEL = "gemini-3.5-flash-lite";
    // 2026-10-30 18:00 UTC = 11:00 Pacific: the last daily reset was 07:00 UTC, the next is 2026-10-31 07:00 UTC.
    private static final Instant NOW = Instant.parse("2026-10-30T18:00:00Z");

    private final GeminiCallAttemptRepository attempts = mock(GeminiCallAttemptRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private GeminiQuotaGuard guard;

    @BeforeEach
    void setUp() {
        QuotaSettings limits = new QuotaSettings("5", "50000", "20", "1033", 0.8);
        guard = new GeminiQuotaGuard(limits, attempts, new GeminiConfig("k", MODEL, "http://127.0.0.1:9"), clock);
        EntityManager entityManager = mock(EntityManager.class);
        Query lock = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lock);
        when(lock.setParameter(anyString(), any())).thenReturn(lock);
        when(lock.getSingleResult()).thenReturn(1);
        ReflectionTestUtils.setField(guard, "entityManager", entityManager);
        when(attempts.recentPromptTokensWithDocumentSize(eq(MODEL), eq(Outcome.SUCCESS), any())).thenReturn(Collections.<Object[]>emptyList());
    }

    private void callsSince(LocalDateTime since, long count) {
        when(attempts.countStartedSince(MODEL, since)).thenReturn(count);
    }

    @Test
    void anEmptyLedgerAllowsACallWithTheInitialEstimate() {
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isTrue();
        assertThat(check.getEstimatedInputTokens()).isEqualTo(1033);
    }

    @Test
    void requestsPerMinuteStopAtTheBudget() {
        callsSince(now.minusSeconds(60), 4); // floor(5 x 0.8) = 4
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("Requests-per-minute");
        assertThat(check.getNotBefore()).isAfter(now);
    }

    @Test
    void callsArePacedEvenlyOverTheMinute() {
        // budget 4 a minute -> one call every 15 s
        when(attempts.lastStartedAt(MODEL)).thenReturn(now.minusSeconds(10));
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getNotBefore()).isEqualTo(now.plusSeconds(5));

        when(attempts.lastStartedAt(MODEL)).thenReturn(now.minusSeconds(16));
        assertThat(guard.check(null).isAllowed()).isTrue();
    }

    @Test
    void inputTokensPerMinuteStopAtTheBudget() {
        when(attempts.sumInputTokensSince(MODEL, now.minusSeconds(60))).thenReturn(39_500L); // budget 40,000
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("Input-tokens-per-minute");

        when(attempts.sumInputTokensSince(MODEL, now.minusSeconds(60))).thenReturn(30_000L);
        assertThat(guard.check(null).isAllowed()).isTrue();
    }

    @Test
    void theTokenReservationFollowsReportedUsageAndGrowsWithThePdfSize() {
        // Two earlier calls: 2,000 and 4,000 reported prompt tokens for PDFs of 100 kB and 300 kB (6,000 / 400,000 B).
        when(attempts.recentPromptTokensWithDocumentSize(eq(MODEL), eq(Outcome.SUCCESS), any())).thenReturn(Arrays.asList(
                new Object[]{2_000L, 100_000L}, new Object[]{4_000L, 300_000L}));

        assertThat(guard.check(null).getEstimatedInputTokens()).as("unknown size: the average").isEqualTo(3_000);
        assertThat(guard.check(50_000L).getEstimatedInputTokens()).as("a small PDF never reserves less than the average")
                .isEqualTo(3_000);
        assertThat(guard.check(1_000_000L).getEstimatedInputTokens()).as("a large PDF reserves proportionally more")
                .isEqualTo(15_000);

        // The larger reservation is what the TPM budget is checked against: 30,000 used + 15,000 > 40,000.
        when(attempts.sumInputTokensSince(MODEL, now.minusSeconds(60))).thenReturn(30_000L);
        assertThat(guard.check(50_000L).isAllowed()).isTrue();
        GeminiQuotaGuard.Check large = guard.check(1_000_000L);
        assertThat(large.isAllowed()).isFalse();
        assertThat(large.getReason()).contains("TPM").contains("15000");
    }

    @Test
    void requestsPerDayPauseUntilTheResetAndTheResetResumes() {
        LocalDateTime lastReset = guard.lastDailyReset();
        assertThat(lastReset).isEqualTo(LocalDateTime.of(2026, 10, 30, 7, 0));
        callsSince(lastReset, 16); // floor(20 x 0.8) = 16
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("Daily Gemini quota");
        assertThat(check.getNotBefore()).isEqualTo(LocalDateTime.of(2026, 10, 31, 7, 0));

        // After the reset the count since the (new) last reset starts again at 0.
        GeminiQuotaGuard afterReset = new GeminiQuotaGuard(new QuotaSettings("5", "50000", "20", "1033", 0.8), attempts,
                new GeminiConfig("k", MODEL, "http://127.0.0.1:9"), Clock.fixed(Instant.parse("2026-10-31T07:01:00Z"),
                ZoneOffset.UTC));
        ReflectionTestUtils.setField(afterReset, "entityManager", ReflectionTestUtils.getField(guard, "entityManager"));
        assertThat(afterReset.check(null).isAllowed()).isTrue();
    }

    @Test
    void aDailyQuota429FromGooglePausesEvenBelowTheLocalBudget() {
        when(attempts.countOutcomeSince(MODEL, guard.lastDailyReset(), Outcome.DAILY_QUOTA)).thenReturn(1L);
        assertThat(guard.check(null).isAllowed()).isFalse();
        assertThat(guard.status().getPausedUntil()).isEqualTo(LocalDateTime.of(2026, 10, 31, 7, 0));
    }

    @Test
    void aPerMinute429CoolsTheWholeQueueDownForAMinute() {
        when(attempts.lastFinishedWithStatus(MODEL, 429, Outcome.RETRYABLE_ERROR)).thenReturn(now.minusSeconds(20));
        GeminiQuotaGuard.Check check = guard.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("429");
        assertThat(check.getNotBefore()).isEqualTo(now.plusSeconds(40));

        when(attempts.lastFinishedWithStatus(MODEL, 429, Outcome.RETRYABLE_ERROR)).thenReturn(now.minusSeconds(61));
        assertThat(guard.check(null).isAllowed()).isTrue();
    }

    @Test
    void withoutLimitsTheWorkerMayNotCall() {
        GeminiQuotaGuard unconfigured = new GeminiQuotaGuard(new QuotaSettings("", "50000", "20", "1033", 0.8),
                attempts, new GeminiConfig("k", MODEL, "http://127.0.0.1:9"), clock);
        ReflectionTestUtils.setField(unconfigured, "entityManager", ReflectionTestUtils.getField(guard, "entityManager"));
        GeminiQuotaGuard.Check check = unconfigured.check(null);
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("GEMINI_LIMIT_RPM");
    }
}
