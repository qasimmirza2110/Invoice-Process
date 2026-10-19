package com.actify.invoice.quota;

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
 */
@Component
public class QuotaSettings {

    private final Integer rpm;
    private final Integer tpm;
    private final Integer rpd;
    private final Integer initialTokenEstimate;
    private final double safetyMargin;
    private final String problem;

    public QuotaSettings(@Value("${gemini.limits.rpm:}") String rpm,
                         @Value("${gemini.limits.tpm:}") String tpm,
                         @Value("${gemini.limits.rpd:}") String rpd,
                         @Value("${gemini.limits.initial-token-estimate:}") String initialTokenEstimate,
                         @Value("${gemini.limits.safety-margin:0.8}") double safetyMargin) {
        StringBuilder problems = new StringBuilder();
        this.rpm = positive("gemini.limits.rpm (GEMINI_LIMIT_RPM)", rpm, problems);
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
}
