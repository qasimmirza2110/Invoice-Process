package com.actify.invoice.intake;

import com.actify.invoice.dto.BatchDtos.FileIntakeResponse;
import com.actify.invoice.entity.BatchSource;
import com.actify.invoice.entity.BatchStatus;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.UploadBatch;
import com.actify.invoice.exception.BatchNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.UploadBatchRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.service.TestPdfs;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.storage.StoredDocument;
import com.actify.invoice.user.Role;
import com.actify.invoice.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A vendor's ordinary invoice PDF - any name, any content, not one of the repository's sample files - is stored
 * privately and queued; a fake PDF is refused before anything is stored; another vendor's batch does not exist for
 * this vendor; a failed database step deletes the stored file again. No database: the repositories are mocks; the
 * same path against PostgreSQL is covered by the pgtest suite.
 */
class RealPdfUploadIntakeTest {

    private final UploadBatchRepository batches = mock(UploadBatchRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceDocumentRepository documents = mock(InvoiceDocumentRepository.class);
    private final ExtractionJobRepository jobs = mock(ExtractionJobRepository.class);
    private final InvoiceDocumentStorage storage = mock(InvoiceDocumentStorage.class);
    private final WorkflowService workflow = mock(WorkflowService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final UploadBatch batch = mock(UploadBatch.class);
    private BatchIntakeService service;

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(3L, "accounts@vendor-firm.co.in", "Vendor", Role.VENDOR));
        when(batch.getId()).thenReturn(12L);
        when(batch.getOwnerUserId()).thenReturn(3L);
        when(batch.getStatus()).thenReturn(BatchStatus.OPEN);
        when(batch.getAcceptedCount()).thenReturn(0);
        when(batch.getSource()).thenReturn(BatchSource.LOCAL);
        when(batch.getRejections()).thenReturn("[]");
        when(batches.findById(12L)).thenReturn(Optional.of(batch));
        when(batches.findByIdForUpdate(12L)).thenReturn(Optional.of(batch));
        when(invoices.countByOwnerAndSha256(anyLong(), anyString())).thenReturn(0L);
        when(documents.save(any(InvoiceDocument.class))).thenAnswer(i -> i.getArgument(0));
        when(invoices.save(any(Invoice.class))).thenAnswer(i -> {
            Invoice saved = i.getArgument(0);
            saved.setId(501L);
            return saved;
        });

        service = spy(new BatchIntakeService(batches, invoices, documents, jobs, storage, workflow, currentUser,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), new ObjectMapper(), null, null, 3));
        doNothing().when(service).lockVendor(anyLong()); // the row lock needs a real EntityManager
    }

    private static MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    @Test
    void anOrdinaryVendorInvoicePdfIsStoredAndQueued() {
        byte[] pdf = TestPdfs.onePage("Vendor Firm Pvt Ltd - INV/2026/118");
        String sha = InvoiceDocumentStorage.sha256Hex(pdf);
        when(storage.store(pdf)).thenReturn(new StoredDocument("6f1c2b8e-1d3a-4c55-9a0e-2f4b7c9d1e00", sha, pdf.length));

        FileIntakeResponse response = service.addFile(12L, upload("INV-2026-118 Vendor Firm.pdf", pdf), null);

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.QUEUED);
        assertThat(response.getInvoiceId()).isEqualTo(501L);
        assertThat(response.getFileName()).isEqualTo("INV-2026-118 Vendor Firm.pdf");
        assertThat(response.getMessage()).isEqualTo("Accepted and queued for extraction."); // not "extracted"

        ArgumentCaptor<InvoiceDocument> document = ArgumentCaptor.forClass(InvoiceDocument.class);
        verify(documents).save(document.capture());
        assertThat(document.getValue().getSha256()).isEqualTo(sha);
        ArgumentCaptor<Invoice> invoice = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).save(invoice.capture());
        assertThat(invoice.getValue().getStatus()).isEqualTo(InvoiceStatus.QUEUED);
        verify(jobs).save(any(ExtractionJob.class));
        verify(workflow).recordCreated(any(Invoice.class), any());
        verify(batch).countAccepted();
    }

    @Test
    void aFakePdfIsRejectedAndNeverStored() {
        byte[] fake = "%PDF-1.7\n<script>alert(1)</script>\n".getBytes(StandardCharsets.US_ASCII);
        FileIntakeResponse response = service.addFile(12L, upload("invoice.pdf", fake), null);

        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getCode()).isEqualTo("INVALID_FILE");
        verify(storage, never()).store(any());
        verify(invoices, never()).save(any());
    }

    @Test
    void anotherVendorsBatchDoesNotExistForThisVendor() {
        when(batch.getOwnerUserId()).thenReturn(99L);
        assertThatThrownBy(() -> service.addFile(12L, upload("a.pdf", TestPdfs.onePage("a")), null))
                .isInstanceOf(BatchNotFoundException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void theStoredFileIsDeletedWhenTheDatabaseStepFails() {
        byte[] pdf = TestPdfs.onePage("db failure");
        when(storage.store(pdf)).thenReturn(new StoredDocument("0b7a1f52-9a51-4f4e-8c1c-3a6d2f9e8b11",
                InvoiceDocumentStorage.sha256Hex(pdf), pdf.length));
        when(documents.save(any(InvoiceDocument.class))).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.addFile(12L, upload("b.pdf", pdf), null)).isInstanceOf(IllegalStateException.class);
        verify(storage).deleteQuietly("0b7a1f52-9a51-4f4e-8c1c-3a6d2f9e8b11");
    }

    @Test
    void aStorageOutageCreatesNoInvoiceNoJobAndNoFalseQueuedResult() {
        byte[] pdf = TestPdfs.onePage("bucket unreachable");
        when(storage.store(pdf)).thenThrow(new DocumentStorageException("Could not store the original PDF"));

        // The client gets the storage error (HTTP 500, GlobalExceptionHandler), never an "accepted / QUEUED" answer.
        assertThatThrownBy(() -> service.addFile(12L, upload("c.pdf", pdf), null))
                .isInstanceOf(DocumentStorageException.class);
        verify(documents, never()).save(any());
        verify(invoices, never()).save(any());
        verify(jobs, never()).save(any());
        verify(batch, never()).countAccepted();
    }
}
