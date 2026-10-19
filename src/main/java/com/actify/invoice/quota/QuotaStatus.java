package com.actify.invoice.quota;

import java.time.LocalDateTime;

/**
 * The Gemini pacing state for the admin dashboard. "usedToday" counts calls since the last midnight Pacific time,
 * because that is when Google resets the daily quota. All counts are this application's own calls only.
 */
public class QuotaStatus {

    private final boolean configured;
    private final String configurationProblem;
    private final String model;
    private final Integer rpmLimit;
    private final Integer tpmLimit;
    private final Integer rpdLimit;
    private final double safetyMargin;
    private final long requestsLastMinute;
    private final long inputTokensLastMinute;
    private final long requestsSinceDailyReset;
    private final LocalDateTime pausedUntil;
    private final LocalDateTime nextDailyReset;
    private final int estimatedInputTokensPerCall;

    public QuotaStatus(boolean configured, String configurationProblem, String model, Integer rpmLimit, Integer tpmLimit,
                       Integer rpdLimit, double safetyMargin, long requestsLastMinute, long inputTokensLastMinute,
                       long requestsSinceDailyReset, LocalDateTime pausedUntil, LocalDateTime nextDailyReset,
                       int estimatedInputTokensPerCall) {
        this.configured = configured;
        this.configurationProblem = configurationProblem;
        this.model = model;
        this.rpmLimit = rpmLimit;
        this.tpmLimit = tpmLimit;
        this.rpdLimit = rpdLimit;
        this.safetyMargin = safetyMargin;
        this.requestsLastMinute = requestsLastMinute;
        this.inputTokensLastMinute = inputTokensLastMinute;
        this.requestsSinceDailyReset = requestsSinceDailyReset;
        this.pausedUntil = pausedUntil;
        this.nextDailyReset = nextDailyReset;
        this.estimatedInputTokensPerCall = estimatedInputTokensPerCall;
    }

    public boolean isConfigured() { return configured; }
    public String getConfigurationProblem() { return configurationProblem; }
    public String getModel() { return model; }
    public Integer getRpmLimit() { return rpmLimit; }
    public Integer getTpmLimit() { return tpmLimit; }
    public Integer getRpdLimit() { return rpdLimit; }
    public double getSafetyMargin() { return safetyMargin; }
    public long getRequestsLastMinute() { return requestsLastMinute; }
    /** Gemini-reported prompt tokens where known, otherwise this app's estimate (labelled as such in the UI). */
    public long getInputTokensLastMinute() { return inputTokensLastMinute; }
    public long getRequestsSinceDailyReset() { return requestsSinceDailyReset; }
    public LocalDateTime getPausedUntil() { return pausedUntil; }
    public LocalDateTime getNextDailyReset() { return nextDailyReset; }
    public int getEstimatedInputTokensPerCall() { return estimatedInputTokensPerCall; }
}
