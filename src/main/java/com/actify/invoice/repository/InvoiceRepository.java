package com.actify.invoice.repository;

import com.actify.invoice.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Invoices: reads for lists and detail pages, plus the row lock every correction takes first. The owner-scoped
 * duplicate queries never compare one vendor's invoices with another vendor's, so a vendor learns nothing about
 * other vendors' uploads. Search filters are built as Specifications in InvoiceListService.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /**
     * SELECT ... FOR UPDATE on the invoice row. Every correction takes this lock first, so two saves
     * of the same invoice run one after the other and the second one sees the first one's version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    /** The invoice that owns a stored PDF (used to authorize PDF downloads through their invoice). */
    Optional<Invoice> findFirstBySourceDocument_IdOrderByIdAsc(String documentId);

    List<Invoice> findByBatchIdOrderByIdAsc(Long batchId);

    /** Same PDF bytes already uploaded by this owner (any batch)? Used to reject duplicates at intake. */
    @Query("select count(i) from Invoice i where i.ownerUserId = :ownerId and i.sourceDocument.sha256 = :sha256")
    long countByOwnerAndSha256(@Param("ownerId") Long ownerId, @Param("sha256") String sha256);

    /** Same PDF bytes on ANOTHER invoice of the SAME owner? -> DUPLICATE_FILE review reason (never across vendors). */
    @Query("select count(i) from Invoice i where i.ownerUserId = :ownerId and i.id <> :invoiceId "
            + "and i.sourceDocument.sha256 = :sha256")
    long countOtherOwnInvoicesWithSha256(@Param("ownerId") Long ownerId, @Param("invoiceId") Long invoiceId,
                                         @Param("sha256") String sha256);

    /**
     * The same owner's other invoices that already have an invoice number and still count (not FAILED / REJECTED),
     * oldest first. DuplicateInvoiceFinder compares normalized number + seller in Java.
     */
    @Query("select i from Invoice i where i.ownerUserId = :ownerId and i.id <> :invoiceId and i.invoiceNumber is not null "
            + "and i.status not in (com.actify.invoice.entity.InvoiceStatus.FAILED, com.actify.invoice.entity.InvoiceStatus.REJECTED) "
            + "order by i.id")
    List<Invoice> findDuplicateCandidates(@Param("ownerId") Long ownerId, @Param("invoiceId") Long invoiceId);
}
