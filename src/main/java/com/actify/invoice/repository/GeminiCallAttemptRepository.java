package com.actify.invoice.repository;

import com.actify.invoice.entity.GeminiCallAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The ledger of every Gemini call this application started (one GeminiCallAttempt row per call, whatever its
 * outcome). GeminiQuotaGuard counts these rows to stay inside the per-minute and per-day limits, and the dashboards
 * report token usage from them. All quota queries are per model, because Google counts limits per project and model.
 * The rows live in PostgreSQL, so a restart of the service never resets the application-side counters.
 */
public interface GeminiCallAttemptRepository extends JpaRepository<GeminiCallAttempt, Long> {

    /**
     * Every call to this model started since the given time, whatever its outcome (a failed call still used a request).
     * Google counts limits per project AND model, so every quota query is per model.
     */
    @Query("select count(a) from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since")
    long countStartedSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    /** When the most recent call for this model started (any outcome), or null. Used for even pacing. */
    @Query("select max(a.startedAt) from GeminiCallAttempt a where a.modelId = :modelId")
    LocalDateTime lastStartedAt(@Param("modelId") String modelId);

    /** Input tokens used since the given time: Gemini's reported count where known, otherwise our reservation. */
    @Query("select coalesce(sum(coalesce(a.promptTokenCount, a.estimatedInputTokens)), 0) "
            + "from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since")
    long sumInputTokensSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    @Query("select count(a) from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since "
            + "and a.outcome = :outcome")
    long countOutcomeSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since,
                           @Param("outcome") GeminiCallAttempt.Outcome outcome);

    /** Recent real prompt sizes (newest first), to estimate the next call. */
    @Query("select a.promptTokenCount from GeminiCallAttempt a where a.modelId = :modelId and a.promptTokenCount is not null "
            + "and a.outcome = :outcome order by a.id desc")
    List<Long> recentPromptTokenCounts(@Param("modelId") String modelId, @Param("outcome") GeminiCallAttempt.Outcome outcome,
                                       Pageable page);

    /**
     * Recent real prompt sizes together with the size of the PDF that produced them (newest first), so the next
     * reservation can be scaled to the next PDF: Gemini's promptTokenCount covers the PDF AND the extraction prompt.
     * Each row is {promptTokenCount (Long), sizeBytes (Long)}.
     */
    @Query("select a.promptTokenCount, d.sizeBytes from GeminiCallAttempt a join a.invoice i join i.sourceDocument d "
            + "where a.modelId = :modelId and a.promptTokenCount is not null and a.outcome = :outcome order by a.id desc")
    List<Object[]> recentPromptTokensWithDocumentSize(@Param("modelId") String modelId,
                                                      @Param("outcome") GeminiCallAttempt.Outcome outcome, Pageable page);

    /** Gemini-reported output (candidates + thoughts) tokens since the given time. */
    @Query("select coalesce(sum(coalesce(a.candidatesTokenCount, 0) + coalesce(a.thoughtsTokenCount, 0)), 0) "
            + "from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since")
    long sumOutputTokensSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    /** Gemini-reported total tokens since the given time (calls without a report count 0). */
    @Query("select coalesce(sum(coalesce(a.totalTokenCount, 0)), 0) "
            + "from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since")
    long sumTotalTokensSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    /** When the most recent call for this model with this HTTP status and outcome finished, or null (429 cool-down). */
    @Query("select max(a.finishedAt) from GeminiCallAttempt a where a.modelId = :modelId and a.httpStatus = :httpStatus "
            + "and a.outcome = :outcome")
    LocalDateTime lastFinishedWithStatus(@Param("modelId") String modelId, @Param("httpStatus") Integer httpStatus,
                                         @Param("outcome") GeminiCallAttempt.Outcome outcome);

    @Query("select coalesce(max(a.attemptNumber), 0) from GeminiCallAttempt a where a.invoice.id = :invoiceId")
    int maxAttemptNumber(@Param("invoiceId") Long invoiceId);

    /** Pass Outcome.IN_FLIGHT: calls that started before the cutoff and never reported back. */
    @Query("select a from GeminiCallAttempt a where a.outcome = :outcome and a.startedAt < :cutoff")
    List<GeminiCallAttempt> findByOutcomeStartedBefore(@Param("outcome") GeminiCallAttempt.Outcome outcome,
                                                       @Param("cutoff") LocalDateTime cutoff);

    @Query("select a from GeminiCallAttempt a where a.invoice.id = :invoiceId order by a.attemptNumber asc")
    List<GeminiCallAttempt> findByInvoiceId(@Param("invoiceId") Long invoiceId);
}
