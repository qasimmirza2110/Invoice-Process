package com.actify.invoice.repository;

import com.actify.invoice.entity.ExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

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
