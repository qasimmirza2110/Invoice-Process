package com.actify.invoice.repository;

import com.actify.invoice.entity.ExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The extraction queue: one ExtractionJob row per queued invoice. The native queries below are written for
 * PostgreSQL (FOR UPDATE ... SKIP LOCKED) and are the only way a worker claims work.
 */
public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, Long> {

    /**
     * The heart of the queue. In ONE short transaction (see ExtractionJobService.claimNext):
     *  - only QUEUED invoices whose back-off time has passed are eligible;
     *  - oldest first: (queued_at, invoice_id) - the id breaks ties deterministically (FIFO start order);
     *  - FOR UPDATE locks the chosen job row; SKIP LOCKED makes a second worker skip it instead of waiting,
     *    so two workers can never claim the same job (PostgreSQL docs: "queue-like tables").
     */
    @Query(value = "select j.invoice_id from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "where i.status = 'QUEUED' and j.next_attempt_at <= :now "
            + "order by j.queued_at, j.invoice_id limit 1 for update of j skip locked", nativeQuery = true)
    List<Long> lockNextEligible(@Param("now") LocalDateTime now);

    /** Same as lockNextEligible, but only among the named invoices (invoice.worker.only-invoice-ids). */
    @Query(value = "select j.invoice_id from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "where i.status = 'QUEUED' and j.next_attempt_at <= :now and j.invoice_id in (:ids) "
            + "order by j.queued_at, j.invoice_id limit 1 for update of j skip locked", nativeQuery = true)
    List<Long> lockNextEligibleAmong(@Param("now") LocalDateTime now, @Param("ids") Collection<Long> ids);

    /** PROCESSING jobs whose worker claimed them before the cutoff (the worker probably died). */
    @Query("select j from ExtractionJob j where j.invoice.status = com.actify.invoice.entity.InvoiceStatus.PROCESSING "
            + "and j.claimedAt < :cutoff")
    List<ExtractionJob> findStaleClaims(@Param("cutoff") LocalDateTime cutoff);

    /** Same as findStaleClaims, but only among the named invoices (invoice.worker.only-invoice-ids). */
    @Query("select j from ExtractionJob j where j.invoice.status = com.actify.invoice.entity.InvoiceStatus.PROCESSING "
            + "and j.claimedAt < :cutoff and j.invoiceId in :ids")
    List<ExtractionJob> findStaleClaimsAmong(@Param("cutoff") LocalDateTime cutoff, @Param("ids") Collection<Long> ids);

    /** Position in the queue (1 = next to start), counting only eligible-or-waiting QUEUED jobs ahead of this one. */
    @Query(value = "select count(*) from extraction_jobs j join invoices i on i.id = j.invoice_id "
            + "where i.status = 'QUEUED' and (j.queued_at < :queuedAt or (j.queued_at = :queuedAt and j.invoice_id < :invoiceId))",
            nativeQuery = true)
    long countQueuedAhead(@Param("queuedAt") LocalDateTime queuedAt, @Param("invoiceId") Long invoiceId);
}
