package com.actify.invoice.quota;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.queue.BackoffAccess;
import com.actify.invoice.repository.GeminiCallAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Quota settings, the no-defaults rule, the daily reset time and the retry back-off - all without a database. */
class QuotaSettingsTest {

    @Test
    void missingOrInvalidLimitsMeanNotConfiguredAndNameTheProblem() {
        QuotaSettings none = new QuotaSettings("", "", "", "", 0.8);
        assertThat(none.isConfigured()).isFalse();
        assertThat(none.getProblem()).contains("GEMINI_LIMIT_RPM").contains("GEMINI_LIMIT_TPM").contains("GEMINI_LIMIT_RPD")
                .contains("GEMINI_INITIAL_TOKEN_ESTIMATE");

        QuotaSettings invalid = new QuotaSettings("15", "-1", "abc", "1000", 0.8);
        assertThat(invalid.isConfigured()).isFalse();
        assertThat(invalid.getProblem()).contains("must be a positive whole number");

        assertThat(new QuotaSettings("15", "250000", "1000", "3000", 1.5).isConfigured()).isFalse();
    }

    @Test
    void validLimitsAreUsedWithTheSafetyMargin() {
        QuotaSettings s = new QuotaSettings("15", "250000", "1000", "3000", 0.8);
        assertThat(s.isConfigured()).isTrue();
        assertThat(s.budget(15)).isEqualTo(12);
        assertThat(s.budget(1)).isEqualTo(1); // never zero
    }

    @Test
    void workerWithoutConfiguredLimitsIsRefusedButTheLegacyEndpointIsNot() {
        GeminiQuotaGuard guard = guard(new QuotaSettings("", "", "", "", 0.8), Clock.systemDefaultZone());

        GeminiQuotaGuard.Check worker = guard.check(true);
        GeminiQuotaGuard.Check legacy = guard.check(false);

        assertThat(worker.isAllowed()).isFalse();
        assertThat(worker.getReason()).contains("not configured");
        assertThat(legacy.isAllowed()).isTrue();
    }

    @Test
    void dailyResetIsMidnightPacificTimeExpressedInServerTime() {
        // 2026-10-19 10:00 UTC = 03:00 Pacific (PDT, UTC-7) -> next reset 2026-10-20 00:00 PDT = 07:00 UTC = 12:30 IST
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        GeminiQuotaGuard guard = guard(new QuotaSettings("15", "250000", "1000", "3000", 0.8), clock);

        assertThat(guard.nextDailyReset()).isEqualTo(LocalDateTime.of(2026, 10, 20, 12, 30));
        assertThat(guard.lastDailyReset()).isEqualTo(LocalDateTime.of(2026, 10, 19, 12, 30));
    }

    @Test
    void backoffGrowsFourTimesPerAttemptWithJitterAndACap() {
        assertThat(BackoffAccess.seconds(1, 0.0)).isEqualTo(24);    // 30 s * 0.8
        assertThat(BackoffAccess.seconds(1, 0.999)).isEqualTo(36);  // 30 s * ~1.2
        assertThat(BackoffAccess.seconds(2, 0.5)).isEqualTo(120);   // 2 min
        assertThat(BackoffAccess.seconds(3, 0.5)).isEqualTo(480);   // 8 min
        assertThat(BackoffAccess.seconds(10, 0.5)).isEqualTo(900);  // capped at 15 min
    }

    private static GeminiQuotaGuard guard(QuotaSettings settings, Clock clock) {
        GeminiCallAttemptRepository attempts = mock(GeminiCallAttemptRepository.class);
        GeminiQuotaGuard guard = new GeminiQuotaGuard(settings, attempts, new GeminiConfig("k", "test-model", "http://x"), clock);
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), anyLong())).thenReturn(query);
        ReflectionTestUtils.setField(guard, "entityManager", em);
        return guard;
    }
}
