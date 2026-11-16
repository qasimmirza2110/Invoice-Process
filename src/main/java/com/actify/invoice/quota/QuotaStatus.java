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

    // ---- Added for the free-tier monitor (backward compatible: new JSON fields only) ----

    private long inputTokensSinceDailyReset;
    private long outputTokensSinceDailyReset;
    private long totalTokensSinceDailyReset;
    private boolean minuteRequestBudgetExhausted;
    private boolean minuteTokenBudgetExhausted;
    private boolean dailyBudgetExhausted;
    private LocalDateTime rateLimitCooldownUntil;
    private Integer rpmBudget;
    private Integer tpmBudget;
    private Integer rpdBudget;

    /** Gemini-reported token usage (input where unreported = the reservation) since the last midnight Pacific time. */
    public void setUsageSinceDailyReset(long input, long output, long total) {
        this.inputTokensSinceDailyReset = input;
        this.outputTokensSinceDailyReset = output;
        this.totalTokensSinceDailyReset = total;
    }

    /** Which budget currently stops new calls; the budgets are limit x safety margin (what the worker really uses). */
    public void setExhaustion(boolean minuteRequests, boolean minuteTokens, boolean daily, LocalDateTime cooldownUntil,
                              int rpmBudget, int tpmBudget, int rpdBudget) {
        this.minuteRequestBudgetExhausted = minuteRequests;
        this.minuteTokenBudgetExhausted = minuteTokens;
        this.dailyBudgetExhausted = daily;
        this.rateLimitCooldownUntil = cooldownUntil;
        this.rpmBudget = rpmBudget;
        this.tpmBudget = tpmBudget;
        this.rpdBudget = rpdBudget;
    }

    public long getInputTokensSinceDailyReset() { return inputTokensSinceDailyReset; }
    public long getOutputTokensSinceDailyReset() { return outputTokensSinceDailyReset; }
    public long getTotalTokensSinceDailyReset() { return totalTokensSinceDailyReset; }
    public boolean isMinuteRequestBudgetExhausted() { return minuteRequestBudgetExhausted; }
    public boolean isMinuteTokenBudgetExhausted() { return minuteTokenBudgetExhausted; }
    public boolean isDailyBudgetExhausted() { return dailyBudgetExhausted; }
    /** Set while a per-minute 429 from Gemini keeps every call on hold, else null. */
    public LocalDateTime getRateLimitCooldownUntil() { return rateLimitCooldownUntil; }
    public Integer getRpmBudget() { return rpmBudget; }
    public Integer getTpmBudget() { return tpmBudget; }
    public Integer getRpdBudget() { return rpdBudget; }
}
