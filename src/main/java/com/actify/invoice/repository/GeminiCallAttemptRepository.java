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

    /** Input tokens used since the given time: Gemini's reported count where known, otherwise our estimate. */
    @Query("select coalesce(sum(coalesce(a.promptTokenCount, a.estimatedInputTokens)), 0) "
            + "from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since")
    long sumInputTokensSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since);

    @Query("select count(a) from GeminiCallAttempt a where a.modelId = :modelId and a.startedAt >= :since "
            + "and a.outcome = :outcome")
    long countOutcomeSince(@Param("modelId") String modelId, @Param("since") LocalDateTime since,
                           @Param("outcome") GeminiCallAttempt.Outcome outcome);

    /** Recent real prompt sizes, to estimate the next call (no PDF page counting needed). */
    @Query("select a.promptTokenCount from GeminiCallAttempt a where a.modelId = :modelId and a.promptTokenCount is not null "
            + "and a.outcome = :outcome order by a.id desc")
    List<Long> recentPromptTokenCounts(@Param("modelId") String modelId, @Param("outcome") GeminiCallAttempt.Outcome outcome,
                                       Pageable page);

    @Query("select coalesce(max(a.attemptNumber), 0) from GeminiCallAttempt a where a.invoice.id = :invoiceId")
    int maxAttemptNumber(@Param("invoiceId") Long invoiceId);

    /** Pass Outcome.IN_FLIGHT: calls that started before the cutoff and never reported back. */
    @Query("select a from GeminiCallAttempt a where a.outcome = :outcome and a.startedAt < :cutoff")
    List<GeminiCallAttempt> findByOutcomeStartedBefore(@Param("outcome") GeminiCallAttempt.Outcome outcome,
                                                       @Param("cutoff") LocalDateTime cutoff);

    @Query("select a from GeminiCallAttempt a where a.invoice.id = :invoiceId order by a.attemptNumber asc")
    List<GeminiCallAttempt> findByInvoiceId(@Param("invoiceId") Long invoiceId);
}
