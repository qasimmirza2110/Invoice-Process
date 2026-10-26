package com.actify.invoice.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Basic brute-force protection for the login endpoint.
 *
 * Rule: 5 failed logins for the same email OR from the same client address -> that email / address is locked
 * for 15 minutes. Counting starts at the first failure; failures older than 15 minutes are forgotten.
 *
 * Limitations (documented on purpose):
 *  - The state lives in this JVM's memory: a restart resets it, and a second app instance would not share it.
 *  - The app is bound to 127.0.0.1, so in the local demo every browser has the SAME client address. Five failed
 *    logins from any accounts therefore lock all logins for 15 minutes (or until a restart).
 *  - The address is request.getRemoteAddr(); this class never reads X-Forwarded-For. Locally there is no proxy. In the
 *    render profile (server.forward-headers-strategy=native) Tomcat substitutes the client address only when the
 *    request comes from an internal proxy; whether Render's proxy qualifies must be verified after deployment
 *    (docs/RENDER_DEPLOYMENT.md, section 9) - otherwise all clients share one address and one lock.
 */
@Component
public class LoginAttemptLimiter {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final Map<String, Attempts> attempts = new HashMap<>();

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    // For tests: a controllable clock.
    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isLocked(String normalizedEmail, String clientAddress) {
        return isLocked(emailKey(normalizedEmail)) || isLocked(addressKey(clientAddress));
    }

    public synchronized void recordFailure(String normalizedEmail, String clientAddress) {
        removeExpired();
        recordFailure(emailKey(normalizedEmail));
        recordFailure(addressKey(clientAddress));
    }

    /** A successful login clears the email's failures (not the address's: one good account must not reset it). */
    public synchronized void recordSuccess(String normalizedEmail) {
        attempts.remove(emailKey(normalizedEmail));
    }

    private boolean isLocked(String key) {
        Attempts a = attempts.get(key);
        return a != null && a.lockedUntil != null && clock.instant().isBefore(a.lockedUntil);
    }

    private void recordFailure(String key) {
        Instant now = clock.instant();
        Attempts a = attempts.get(key);
        if (a == null || a.isExpired(now)) {
            a = new Attempts(now);
            attempts.put(key, a);
        }
        a.failures++;
        if (a.failures >= MAX_FAILURES && a.lockedUntil == null) {
            a.lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    // Keeps memory bounded even if someone tries thousands of different emails.
    private void removeExpired() {
        if (attempts.size() < MAX_TRACKED_KEYS) {
            return;
        }
        Instant now = clock.instant();
        for (Iterator<Attempts> it = attempts.values().iterator(); it.hasNext(); ) {
            if (it.next().isExpired(now)) {
                it.remove();
            }
        }
    }

    private static String emailKey(String normalizedEmail) {
        return "email:" + normalizedEmail;
    }

    private static String addressKey(String clientAddress) {
        return "addr:" + clientAddress;
    }

    private static final class Attempts {
        private final Instant firstFailure;
        private int failures;
        private Instant lockedUntil;

        private Attempts(Instant firstFailure) {
            this.firstFailure = firstFailure;
        }

        private boolean isExpired(Instant now) {
            if (lockedUntil != null) {
                return !now.isBefore(lockedUntil);
            }
            return !now.isBefore(firstFailure.plus(LOCK_DURATION));
        }
    }
}
