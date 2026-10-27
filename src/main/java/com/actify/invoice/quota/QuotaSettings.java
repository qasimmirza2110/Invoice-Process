package com.actify.invoice.quota;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Gemini limits of YOUR Google project and model, copied from Google AI Studio by the owner.
 * There are deliberately NO default numbers: limits differ per model, project and tier, and a guessed value would
 * either waste quota or hide a problem. Without valid settings the background worker stays off.
 *
 *  gemini.limits.rpm                     requests per minute
 *  gemini.limits.tpm                     input tokens per minute
 *  gemini.limits.rpd                     requests per day (Google resets it at midnight Pacific time)
 *  gemini.limits.initial-token-estimate  input tokens per invoice to assume before any real usage is recorded
 *  gemini.limits.safety-margin           use only this share of each limit (default 0.8) - other users of the same
 *                                        Google project (AI Studio, Postman, other apps) are invisible to this app
 *  gemini.limits.business-max-rpm        the project's own ceiling (default 15): the effective RPM is the lower of this
 *                                        and gemini.limits.rpm
 * Calls are also spaced evenly (minIntervalMillis), never fired in a burst.
 */
@Component
public class QuotaSettings {

    private final Integer rpm;
    private final Integer tpm;
    private final Integer rpd;
    private final Integer initialTokenEstimate;
    private final double safetyMargin;
    private final boolean evenPacing;
    private final String problem;

    /** Without the business cap (tests and the startup guard): exactly the given limits, even pacing on. */
    public QuotaSettings(String rpm, String tpm, String rpd, String initialTokenEstimate, double safetyMargin) {
        this(rpm, tpm, rpd, initialTokenEstimate, safetyMargin, String.valueOf(Integer.MAX_VALUE), true);
    }

    /**
     * @param businessMaxRpm the project's own ceiling (gemini.limits.business-max-rpm, default 15): the effective
     *                       requests-per-minute limit is the LOWER of this and the AI Studio value (GEMINI_LIMIT_RPM).
     * @param evenPacing     gemini.limits.even-pacing (default true): space call starts by minIntervalMillis().
     */
    @Autowired
    public QuotaSettings(@Value("${gemini.limits.rpm:}") String rpm,
                         @Value("${gemini.limits.tpm:}") String tpm,
                         @Value("${gemini.limits.rpd:}") String rpd,
                         @Value("${gemini.limits.initial-token-estimate:}") String initialTokenEstimate,
                         @Value("${gemini.limits.safety-margin:0.8}") double safetyMargin,
                         @Value("${gemini.limits.business-max-rpm:15}") String businessMaxRpm,
                         @Value("${gemini.limits.even-pacing:true}") boolean evenPacing) {
        this.evenPacing = evenPacing;
        StringBuilder problems = new StringBuilder();
        Integer configuredRpm = positive("gemini.limits.rpm (GEMINI_LIMIT_RPM)", rpm, problems);
        Integer cap = positive("gemini.limits.business-max-rpm", businessMaxRpm, problems);
        // Integer.valueOf: a bare int branch would unbox a null configuredRpm (NullPointerException).
        this.rpm = configuredRpm == null || cap == null ? configuredRpm : Integer.valueOf(Math.min(configuredRpm, cap));
        this.tpm = positive("gemini.limits.tpm (GEMINI_LIMIT_TPM)", tpm, problems);
        this.rpd = positive("gemini.limits.rpd (GEMINI_LIMIT_RPD)", rpd, problems);
        this.initialTokenEstimate = positive("gemini.limits.initial-token-estimate (GEMINI_INITIAL_TOKEN_ESTIMATE)",
                initialTokenEstimate, problems);
        if (safetyMargin <= 0 || safetyMargin > 1) {
            problems.append("gemini.limits.safety-margin must be greater than 0 and at most 1. ");
        }
        this.safetyMargin = safetyMargin;
        this.problem = problems.length() == 0 ? null : problems.toString().trim();
    }

    private static Integer positive(String name, String raw, StringBuilder problems) {
        if (raw == null || raw.trim().isEmpty()) {
            problems.append(name).append(" is not set. ");
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        problems.append(name).append(" must be a positive whole number. ");
        return null;
    }

    /** True when every limit is a valid positive number. */
    public boolean isConfigured() {
        return problem == null;
    }

    /** Why the settings are not usable (for the admin dashboard), or null. */
    public String getProblem() {
        return problem;
    }

    public Integer getRpm() { return rpm; }
    public Integer getTpm() { return tpm; }
    public Integer getRpd() { return rpd; }
    public Integer getInitialTokenEstimate() { return initialTokenEstimate; }
    public double getSafetyMargin() { return safetyMargin; }

    /** The share of a limit this app allows itself, rounded down, at least 1. */
    public int budget(int limit) {
        return Math.max(1, (int) Math.floor(limit * safetyMargin));
    }

    /**
     * Even pacing: the shortest gap between two call STARTS, so the minute budget is spread over the minute instead of
     * being used in one burst. 15 RPM x 0.8 = 12 calls -> one call every 5 s. 0 when not configured.
     */
    public long minIntervalMillis() {
        return rpm == null || !evenPacing ? 0 : (long) Math.ceil(60_000.0 / budget(rpm));
    }
}
