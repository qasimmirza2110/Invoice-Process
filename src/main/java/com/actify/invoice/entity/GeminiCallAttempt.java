package com.actify.invoice.entity;

import com.actify.invoice.dto.TokenUsage;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * ONE row per real HTTP call to Gemini (worker AND the legacy /extract endpoint). Written BEFORE the call
 * (outcome IN_FLIGHT, so a crash still counts against the quota) and completed after it.
 *
 * Token columns hold what Gemini REPORTED (usageMetadata) and stay null when it reported nothing.
 * estimated_input_tokens is this app's own guess used for pacing - never shown as real usage.
 * The invoices.*_token_count columns keep meaning "the last call of this invoice" for backward compatibility.
 */
@Entity
@Table(name = "gemini_call_attempts",
        uniqueConstraints = @UniqueConstraint(name = "uk_gemini_attempts_invoice_attempt", columnNames = {"invoice_id", "attempt_number"}),
        indexes = @Index(name = "ix_gemini_attempts_started", columnList = "started_at"))
public class GeminiCallAttempt {

    public enum Caller { WORKER, LEGACY_EXTRACT }

    public enum Outcome { IN_FLIGHT, SUCCESS, RETRYABLE_ERROR, DAILY_QUOTA, PERMANENT_ERROR, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_gemini_attempts_invoice"))
    private Invoice invoice;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "caller", length = 20, nullable = false, updatable = false)
    private Caller caller;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "model_id", length = 100, nullable = false, updatable = false)
    private String modelId;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private Outcome outcome = Outcome.IN_FLIGHT;

    @Column(name = "error_code", length = 200)
    private String errorCode;

    @Column(name = "prompt_token_count")
    private Long promptTokenCount;

    @Column(name = "candidates_token_count")
    private Long candidatesTokenCount;

    @Column(name = "thoughts_token_count")
    private Long thoughtsTokenCount;

    @Column(name = "total_token_count")
    private Long totalTokenCount;

    @Column(name = "estimated_input_tokens", nullable = false, updatable = false)
    private int estimatedInputTokens;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected GeminiCallAttempt() {
    }

    public GeminiCallAttempt(Invoice invoice, int attemptNumber, Caller caller, String modelId,
                             LocalDateTime startedAt, int estimatedInputTokens) {
        this.invoice = invoice;
        this.attemptNumber = attemptNumber;
        this.caller = caller;
        this.modelId = modelId;
        this.startedAt = startedAt;
        this.estimatedInputTokens = estimatedInputTokens;
    }

    public void complete(Outcome outcome, Integer httpStatus, String errorCode, TokenUsage usage, long durationMs,
                         LocalDateTime finishedAt) {
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode != null && errorCode.length() > 200 ? errorCode.substring(0, 200) : errorCode;
        this.durationMs = durationMs;
        this.finishedAt = finishedAt;
        if (usage != null) {
            this.modelVersion = usage.getModelVersion();
            this.promptTokenCount = usage.getPromptTokenCount();
            this.candidatesTokenCount = usage.getCandidatesTokenCount();
            this.thoughtsTokenCount = usage.getThoughtsTokenCount();
            this.totalTokenCount = usage.getTotalTokenCount();
        }
    }

    /** A call that started but never reported back (the process died): it still counts against the quota. */
    public void markUnknown(LocalDateTime now) {
        this.outcome = Outcome.UNKNOWN;
        this.finishedAt = now;
    }

    public Long getId() { return id; }
    public int getAttemptNumber() { return attemptNumber; }
    public Caller getCaller() { return caller; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public String getModelId() { return modelId; }
    public String getModelVersion() { return modelVersion; }
    public Integer getHttpStatus() { return httpStatus; }
    public Outcome getOutcome() { return outcome; }
    public String getErrorCode() { return errorCode; }
    public Long getPromptTokenCount() { return promptTokenCount; }
    public Long getCandidatesTokenCount() { return candidatesTokenCount; }
    public Long getThoughtsTokenCount() { return thoughtsTokenCount; }
    public Long getTotalTokenCount() { return totalTokenCount; }
    public int getEstimatedInputTokens() { return estimatedInputTokens; }
    public Long getDurationMs() { return durationMs; }
}
