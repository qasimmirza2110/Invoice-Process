package com.actify.invoice.repository;

import com.actify.invoice.entity.InvoiceVersion;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Append-only on purpose: it extends the bare Repository and only exposes save and reads,
 * so there is no update or delete path for a historical version anywhere in the application.
 */
public interface InvoiceVersionRepository extends Repository<InvoiceVersion, Long> {

    InvoiceVersion save(InvoiceVersion version);

    /** 0 when the invoice has no versions yet (FAILED uploads and invoices from before version history). */
    @Query("select coalesce(max(v.versionNumber), 0) from InvoiceVersion v where v.invoice.id = :invoiceId")
    int findCurrentVersionNumber(@Param("invoiceId") Long invoiceId);

    List<InvoiceVersion> findByInvoice_IdOrderByVersionNumberDesc(Long invoiceId);

    Optional<InvoiceVersion> findByInvoice_IdAndVersionNumber(Long invoiceId, Integer versionNumber);
}
