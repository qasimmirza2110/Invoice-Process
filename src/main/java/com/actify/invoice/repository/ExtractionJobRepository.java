package com.actify.invoice.repository;

import com.actify.invoice.entity.ExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The extraction queue: one ExtractionJob row per queued invoice. The native queries below are written for
 * PostgreSQL (FOR UPDATE ... SKIP LOCKED) and are the only way a worker claims work.
 */
public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, Long> {

    /**
     * The heart of the queue. In ONE short transaction (see ExtractionJobService.claimNext):
     *  - only QUEUED invoices whose back-off time has passed are eligible;
     *  - only invoices of a batch whose uploader gave the explicit free-tier consent (upload_batches.free_tier_consent):
     *    legacy invoices (no batch), uploads without consent and batches older than the consent column are never
     *    returned, so they can never reach Gemini;
     *  - oldest first: (queued_at, invoice_id) - the id breaks ties deterministically (FIFO start order);
     *  - FOR UPDATE locks the chosen job row; SKIP LOCKED makes a second worker skip it instead of waiting,
     *    so two workers can never claim the same job (PostgreSQL docs: "queue-like tables").
     */
    @Query(value = "select j.invoice_id from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "join upload_batches b on b.id = i.batch_id "
            + "where i.status = 'QUEUED' and j.next_attempt_at <= :now and b.free_tier_consent = true "
            + "order by j.queued_at, j.invoice_id limit 1 for update of j skip locked", nativeQuery = true)
    List<Long> lockNextFreeTierEligible(@Param("now") LocalDateTime now);

    /** How many jobs lockNextFreeTierEligible could pick right now (no lock; for the admin queue status). */
    @Query(value = "select count(*) from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "join upload_batches b on b.id = i.batch_id "
            + "where i.status = 'QUEUED' and j.next_attempt_at <= :now and b.free_tier_consent = true", nativeQuery = true)
    long countFreeTierEligible(@Param("now") LocalDateTime now);

    /** QUEUED consented invoices whose next attempt lies in the future (retry back-off or a 429 / daily-quota deferral). */
    @Query(value = "select count(*) from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "join upload_batches b on b.id = i.batch_id "
            + "where i.status = 'QUEUED' and j.next_attempt_at > :now and b.free_tier_consent = true", nativeQuery = true)
    long countFreeTierDeferred(@Param("now") LocalDateTime now);

    /** QUEUED invoices WITHOUT the free-tier consent (no batch, or a batch without it): never sent to Gemini. */
    @Query(value = "select count(*) from invoices i left join upload_batches b on b.id = i.batch_id "
            + "where i.status = 'QUEUED' and coalesce(b.free_tier_consent, false) = false", nativeQuery = true)
    long countQueuedWithoutFreeTierConsent();

    /** PROCESSING jobs whose worker claimed them before the cutoff (the worker probably died). */
    @Query("select j from ExtractionJob j where j.invoice.status = com.actify.invoice.entity.InvoiceStatus.PROCESSING "
            + "and j.claimedAt < :cutoff")
    List<ExtractionJob> findStaleClaims(@Param("cutoff") LocalDateTime cutoff);

    /** Position in the queue (1 = next to start), counting only eligible-or-waiting QUEUED jobs ahead of this one. */
    @Query(value = "select count(*) from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "where i.status = 'QUEUED' and (j.queued_at < :queuedAt or (j.queued_at = :queuedAt and j.invoice_id < :invoiceId))",
            nativeQuery = true)
    long countQueuedAhead(@Param("queuedAt") LocalDateTime queuedAt, @Param("invoiceId") Long invoiceId);
}
