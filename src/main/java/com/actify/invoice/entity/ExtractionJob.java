package com.actify.invoice.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.MapsId;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * Queue mechanics for ONE invoice of the batch workflow (the invoice's business state stays in invoices.status).
 *
 *  - queued_at: when the file was accepted. The worker starts jobs in (queued_at, invoice_id) order (FIFO).
 *    A retried job keeps its original queued_at, so after its wait it runs before newer files.
 *  - next_attempt_at: the job is not eligible before this time (retry back-off).
 *  - claimed_at / claimed_by: set while a worker is extracting; a claim older than the stale limit is recovered.
 */
@Entity
@Table(name = "extraction_jobs",
        indexes = @Index(name = "ix_extraction_jobs_eligible", columnList = "next_attempt_at, queued_at, invoice_id"))
public class ExtractionJob {

    @Id
    @Column(name = "invoice_id")
    private Long invoiceId;

    // The job's primary key IS the invoice id (one job per invoice).
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", foreignKey = @ForeignKey(name = "fk_extraction_jobs_invoice"))
    private Invoice invoice;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private LocalDateTime queuedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    // Client-safe text only (never provider bodies, never invoice content).
    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected ExtractionJob() {
    }

    public ExtractionJob(Invoice invoice, LocalDateTime queuedAt, int maxAttempts) {
        this.invoice = invoice;
        this.queuedAt = queuedAt;
        this.nextAttemptAt = queuedAt;
        this.maxAttempts = maxAttempts;
    }

    public void claim(LocalDateTime now, String worker) {
        attemptCount++;
        claimedAt = now;
        claimedBy = worker;
    }

    /** Back in the queue: eligible again at nextAttemptAt. The original queued_at (FIFO position) is kept. */
    public void scheduleRetry(LocalDateTime nextAttemptAt, String errorCode, String errorMessage) {
        this.nextAttemptAt = nextAttemptAt;
        this.claimedAt = null;
        this.claimedBy = null;
        recordError(errorCode, errorMessage);
    }

    /**
     * Gemini's DAILY quota is used up. That is not this invoice's fault, so the attempt is given back and
     * the job simply waits until the daily reset.
     */
    public void waitForDailyReset(LocalDateTime resetAt, String errorCode, String errorMessage) {
        if (attemptCount > 0) {
            attemptCount--;
        }
        scheduleRetry(resetAt, errorCode, errorMessage);
    }

    public void finish(LocalDateTime now) {
        finishedAt = now;
        claimedAt = null;
        claimedBy = null;
    }

    public void fail(LocalDateTime now, String errorCode, String errorMessage) {
        recordError(errorCode, errorMessage);
        finish(now);
    }

    /** Admin requeue of a failed job: a fresh set of attempts, eligible now. */
    public void requeue(LocalDateTime now) {
        attemptCount = 0;
        nextAttemptAt = now;
        finishedAt = null;
        claimedAt = null;
        claimedBy = null;
    }

    private void recordError(String errorCode, String errorMessage) {
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
    }

    public boolean hasAttemptsLeft() {
        return attemptCount < maxAttempts;
    }

    public Long getInvoiceId() { return invoiceId; }
    public Invoice getInvoice() { return invoice; }
    public LocalDateTime getQueuedAt() { return queuedAt; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public String getClaimedBy() { return claimedBy; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
}
