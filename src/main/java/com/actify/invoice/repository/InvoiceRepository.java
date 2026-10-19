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

    /** Same PDF bytes on another invoice (another vendor or a legacy invoice)? -> DUPLICATE_FILE review reason. */
    @Query("select count(i) from Invoice i where i.id <> :invoiceId and i.sourceDocument.sha256 = :sha256")
    long countOtherInvoicesWithSha256(@Param("invoiceId") Long invoiceId, @Param("sha256") String sha256);

    /**
     * Same seller + invoice number on another (not failed) invoice? -> DUPLICATE_INVOICE_NO review reason.
     * An invoice number alone is not unique across sellers, so both are compared.
     */
    @Query("select count(i) from Invoice i where i.id <> :invoiceId and i.invoiceNumber = :invoiceNumber "
            + "and lower(i.sellerName) = lower(:sellerName) and i.status <> com.actify.invoice.entity.InvoiceStatus.FAILED")
    long countOtherInvoicesWithSellerAndNumber(@Param("invoiceId") Long invoiceId,
                                               @Param("sellerName") String sellerName,
                                               @Param("invoiceNumber") String invoiceNumber);
}
