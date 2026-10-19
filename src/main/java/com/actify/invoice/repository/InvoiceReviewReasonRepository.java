package com.actify.invoice.repository;

import com.actify.invoice.entity.InvoiceReviewReason;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only: only save and reads. */
public interface InvoiceReviewReasonRepository extends Repository<InvoiceReviewReason, Long> {

    InvoiceReviewReason save(InvoiceReviewReason reason);

    @Query("select r from InvoiceReviewReason r where r.invoice.id = :invoiceId order by r.versionNumber asc, r.id asc")
    List<InvoiceReviewReason> findByInvoiceId(@Param("invoiceId") Long invoiceId);
}
