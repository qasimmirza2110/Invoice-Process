package com.actify.invoice.review;

import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The board's stage names and "why it waits" sentences (the query part is covered by the pgtests). */
class IntakeBoardServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

    private static Invoice invoice(InvoiceStatus status, Long reviewerId) {
        Invoice invoice = new Invoice();
        invoice.setStatus(status);
        ReflectionTestUtils.setField(invoice, "assignedReviewerId", reviewerId);
        return invoice;
    }

    @Test
    void stagesSeparateWaitingForExtractionFromReadyForReview() {
        assertThat(IntakeBoardService.stage(InvoiceStatus.QUEUED)).isEqualTo("WAITING_FOR_EXTRACTION");
        assertThat(IntakeBoardService.stage(InvoiceStatus.PROCESSING)).isEqualTo("EXTRACTING");
        assertThat(IntakeBoardService.stage(InvoiceStatus.NEEDS_REVIEW)).isEqualTo("READY_FOR_REVIEW");
        assertThat(IntakeBoardService.BOARD_STATUSES)
                .containsExactlyInAnyOrder(InvoiceStatus.QUEUED, InvoiceStatus.PROCESSING, InvoiceStatus.NEEDS_REVIEW);
    }

    @Test
    void aQueuedInvoiceSaysTheWorkerIsOffWhenItIs() {
        String why = IntakeBoardService.blockedReason(invoice(InvoiceStatus.QUEUED, null), null, false,
                "The extraction worker is switched off (INVOICE_WORKER_ENABLED is not true).", NOW);
        assertThat(why).startsWith("Waiting for extraction: The extraction worker is switched off");
    }

    @Test
    void aScheduledRetryIsShownWithItsTimeAndLastErrorCode() {
        ExtractionJob job = mock(ExtractionJob.class);
        when(job.getNextAttemptAt()).thenReturn(NOW.plusMinutes(5));
        when(job.getLastErrorCode()).thenReturn("HTTP_503");
        String why = IntakeBoardService.blockedReason(invoice(InvoiceStatus.QUEUED, 7L), job, true, null, NOW);
        assertThat(why).isEqualTo("Next extraction attempt at " + NOW.plusMinutes(5) + " (last attempt: HTTP_503).");
    }

    private static Invoice withConsent(Invoice invoice, boolean consent) {
        invoice.setBatch(new com.actify.invoice.entity.UploadBatch(mock(com.actify.invoice.user.AppUser.class), com.actify.invoice.entity.BatchSource.LOCAL,
                "b", null, consent));
        return invoice;
    }

    @Test
    void extractionStatesSeparateConsentQuotaProgressAndReadiness() {
        Invoice noConsent = withConsent(invoice(InvoiceStatus.QUEUED, null), false);
        Invoice legacy = invoice(InvoiceStatus.QUEUED, null); // no batch at all (pre-migration / legacy)
        Invoice queued = withConsent(invoice(InvoiceStatus.QUEUED, null), true);

        assertThat(IntakeBoardService.extractionState(noConsent, null, true, false, NOW)).isEqualTo("MISSING_FREE_TIER_CONSENT");
        assertThat(IntakeBoardService.extractionState(legacy, null, true, false, NOW)).isEqualTo("MISSING_FREE_TIER_CONSENT");
        assertThat(IntakeBoardService.extractionState(queued, null, true, false, NOW)).isEqualTo("WAITING_FOR_EXTRACTION");
        assertThat(IntakeBoardService.extractionState(queued, null, true, true, NOW)).isEqualTo("WAITING_FOR_QUOTA");
        assertThat(IntakeBoardService.extractionState(queued, null, false, true, NOW)).isEqualTo("WAITING_FOR_EXTRACTION");

        ExtractionJob deferred = mock(ExtractionJob.class);
        when(deferred.getNextAttemptAt()).thenReturn(NOW.plusMinutes(1));
        when(deferred.getLastErrorCode()).thenReturn("HTTP_429");
        assertThat(IntakeBoardService.extractionState(queued, deferred, true, false, NOW)).isEqualTo("WAITING_FOR_QUOTA");

        assertThat(IntakeBoardService.extractionState(invoice(InvoiceStatus.PROCESSING, null), null, true, false, NOW))
                .isEqualTo("EXTRACTING");
        assertThat(IntakeBoardService.extractionState(invoice(InvoiceStatus.NEEDS_REVIEW, null), null, true, false, NOW))
                .isEqualTo("READY_FOR_REVIEWER_ASSIGNMENT");
        assertThat(IntakeBoardService.extractionState(invoice(InvoiceStatus.NEEDS_REVIEW, 7L), null, true, false, NOW))
                .isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void theMissingConsentReasonIsUnderstandableAndNeverPromisesSafety() {
        assertThat(IntakeBoardService.NO_FREE_TIER_CONSENT).contains("without free-tier").contains("never sent")
                .doesNotContainIgnoringCase("safe");
    }

    @Test
    void readyInvoicesOnlyWaitWhenNobodyIsAssigned() {
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.NEEDS_REVIEW, null), null, false, "off", NOW))
                .isEqualTo("Extraction finished; waiting for a reviewer to be assigned.");
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.NEEDS_REVIEW, 7L), null, false, "off", NOW)).isNull();
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.PROCESSING, null), null, true, null, NOW)).isNull();
    }
}
