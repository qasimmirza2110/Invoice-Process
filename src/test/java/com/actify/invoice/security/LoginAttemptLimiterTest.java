package com.actify.invoice.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    /** A clock the test can move forward. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void fifthFailureForAnEmailLocksItForFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a@example.test", "10.0.0." + i);
        }
        assertThat(limiter.isLocked("a@example.test", "10.0.0.99")).isFalse();

        limiter.recordFailure("a@example.test", "10.0.0.5");
        assertThat(limiter.isLocked("a@example.test", "10.0.0.99")).isTrue();

        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertThat(limiter.isLocked("a@example.test", "10.0.0.99")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(limiter.isLocked("a@example.test", "10.0.0.99")).isFalse();
    }

    @Test
    void fiveFailuresFromOneAddressLockThatAddressForEveryEmail() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("user" + i + "@example.test", "127.0.0.1");
        }
        assertThat(limiter.isLocked("someone-else@example.test", "127.0.0.1")).isTrue();
        assertThat(limiter.isLocked("someone-else@example.test", "10.1.1.1")).isFalse();
    }

    @Test
    void successClearsTheEmailCounterButNotTheAddressCounter() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a@example.test", "127.0.0.1");
        }
        limiter.recordSuccess("a@example.test");
        limiter.recordFailure("a@example.test", "10.9.9.9");
        assertThat(limiter.isLocked("a@example.test", "10.9.9.8")).isFalse();

        limiter.recordFailure("b@example.test", "127.0.0.1"); // 5th failure from this address
        assertThat(limiter.isLocked("c@example.test", "127.0.0.1")).isTrue();
    }

    @Test
    void failuresOlderThanTheWindowAreForgotten() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a@example.test", "10.0.0.1");
        }
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        limiter.recordFailure("a@example.test", "10.0.0.2");
        assertThat(limiter.isLocked("a@example.test", "10.0.0.3")).isFalse();
    }
}
