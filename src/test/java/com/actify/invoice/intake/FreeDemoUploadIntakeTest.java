package com.actify.invoice.intake;

import com.actify.invoice.config.FreeDemoUploadPolicyAccess;
import com.actify.invoice.dto.BatchDtos.FileIntakeResponse;
import com.actify.invoice.entity.BatchSource;
import com.actify.invoice.entity.BatchStatus;
import com.actify.invoice.entity.UploadBatch;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.UploadBatchRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.storage.InvoiceDocumentStorage;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Free demo: a file that is not a published fixture is refused FIRST - never stored, recorded under a neutral name -
 * while a fixture continues through the normal checks. Outside the demo nothing changes. No database: the
 * repositories are mocks; the rest of the intake path is covered by BatchQueuePgIntegrationTest.
 */
class FreeDemoUploadIntakeTest {

    private static final byte[] REAL_LOOKING = ("%PDF-1.4\n% pretend this is a real customer's invoice\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIXTURE = "%PDF-1.4\n% the one allowlisted synthetic file\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    private final UploadBatchRepository batches = mock(UploadBatchRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceDocumentStorage storage = mock(InvoiceDocumentStorage.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final UploadBatch batch = mock(UploadBatch.class);

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(3L, "vendor@example.test", "Vendor", Role.VENDOR));
        when(batch.getId()).thenReturn(12L);
        when(batch.getOwnerUserId()).thenReturn(3L);
        when(batch.getStatus()).thenReturn(BatchStatus.OPEN);
        when(batch.getAcceptedCount()).thenReturn(0);
        when(batch.getSource()).thenReturn(BatchSource.LOCAL);
        when(batch.getRejections()).thenReturn("[]");
        when(batches.findById(12L)).thenReturn(Optional.of(batch));
        when(batches.findByIdForUpdate(12L)).thenReturn(Optional.of(batch));
        // Stops a file that passed every earlier check right before storage, with a normal, recognisable rejection.
        when(invoices.countByOwnerAndSha256(anyLong(), anyString())).thenReturn(1L);
    }

    private BatchIntakeService service(boolean demo) {
        return new BatchIntakeService(batches, invoices, mock(InvoiceDocumentRepository.class),
                mock(ExtractionJobRepository.class), storage, mock(WorkflowService.class), currentUser,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), new ObjectMapper(), null, null,
                FreeDemoUploadPolicyAccess.policy(demo, InvoiceDocumentStorage.sha256Hex(FIXTURE)), 3);
    }

    private static MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    @Test
    void aNonFixtureFileIsRejectedFirstNeverStoredAndItsNameIsNotRecorded() {
        FileIntakeResponse response = service(true).addFile(12L, upload("AcmeCorp-INV-2231.pdf", REAL_LOOKING), null);

        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getCode()).isEqualTo("NOT_A_DEMO_FIXTURE");
        assertThat(response.getFileName()).isEqualTo("file-not-accepted-by-demo.pdf");
        verify(storage, never()).store(org.mockito.ArgumentMatchers.any());
        verify(invoices, never()).countByOwnerAndSha256(anyLong(), anyString());   // refused before any other check
        ArgumentCaptor<String> recorded = ArgumentCaptor.forClass(String.class);
        verify(batch).recordRejection(recorded.capture());
        assertThat(recorded.getValue()).contains("NOT_A_DEMO_FIXTURE", "file-not-accepted-by-demo.pdf")
                .doesNotContain("AcmeCorp");
    }

    @Test
    void theRuleRunsBeforeTheOtherRejectionsThatWouldRecordTheRealName() {
        // A nested-folder upload would normally be rejected as NESTED_FOLDER under its own name.
        FileIntakeResponse response = service(true).addFile(12L, upload("AcmeCorp-INV-2231.pdf", REAL_LOOKING),
                "client-folder/sub/AcmeCorp-INV-2231.pdf");
        assertThat(response.getCode()).isEqualTo("NOT_A_DEMO_FIXTURE");
        assertThat(response.getFileName()).doesNotContain("AcmeCorp");
    }

    @Test
    void aPublishedFixtureContinuesThroughTheNormalChecks() {
        FileIntakeResponse response = service(true).addFile(12L, upload("synthetic-demo-invoice-01.pdf", FIXTURE), null);
        assertThat(response.getCode()).isEqualTo("DUPLICATE_FILE");                 // reached the normal checks
        assertThat(response.getFileName()).isEqualTo("synthetic-demo-invoice-01.pdf");
    }

    @Test
    void outsideTheDemoEveryPdfFollowsTheProductionRulesUnchanged() {
        FileIntakeResponse response = service(false).addFile(12L, upload("AcmeCorp-INV-2231.pdf", REAL_LOOKING), null);
        assertThat(response.getCode()).isEqualTo("DUPLICATE_FILE");
        assertThat(response.getFileName()).isEqualTo("AcmeCorp-INV-2231.pdf");
    }
}
