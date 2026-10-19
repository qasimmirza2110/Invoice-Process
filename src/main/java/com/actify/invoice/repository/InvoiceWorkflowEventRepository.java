package com.actify.invoice.repository;

import com.actify.invoice.entity.InvoiceWorkflowEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only: only save and reads, no update or delete anywhere in the application. */
public interface InvoiceWorkflowEventRepository extends Repository<InvoiceWorkflowEvent, Long> {

    InvoiceWorkflowEvent save(InvoiceWorkflowEvent event);

    List<InvoiceWorkflowEvent> findByInvoiceIdOrderByIdAsc(Long invoiceId);
}
