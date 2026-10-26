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

    @Test
    void allowlistedIdsThatCanNeverBeExtractedAreNamedWithTheReason() {
        Invoice queued = invoice(InvoiceStatus.QUEUED, null);
        ReflectionTestUtils.setField(queued, "id", 20L);
        ReflectionTestUtils.setField(queued, "ownerUserId", 3L);
        Invoice done = invoice(InvoiceStatus.NEEDS_REVIEW, null);
        ReflectionTestUtils.setField(done, "id", 21L);
        ReflectionTestUtils.setField(done, "ownerUserId", 3L);
        Invoice legacy = invoice(InvoiceStatus.EXTRACTED, null);
        ReflectionTestUtils.setField(legacy, "id", 22L);
        java.util.Set<Long> only = new java.util.TreeSet<>(java.util.Arrays.asList(20L, 21L, 22L, 99L));

        java.util.List<String> problems = IntakeBoardService.allowlistProblems(only, java.util.Arrays.asList(queued, done, legacy));

        assertThat(problems).containsExactly(
                "Invoice 21 is NEEDS_REVIEW, not QUEUED; it will not be extracted again.",
                "Invoice 22 is a legacy invoice without an extraction job; it is never extracted by the worker.",
                "Invoice 99 does not exist, so the worker has nothing to extract for it.");
        assertThat(IntakeBoardService.allowlistProblems(java.util.Collections.<Long>emptySet(), java.util.Collections.<Invoice>emptyList())).isEmpty();
    }

    @Test
    void aWorkerLimitedToNamedInvoicesSaysWhyEveryOtherInvoiceStaysQueued() {
        Invoice other = invoice(InvoiceStatus.QUEUED, null);
        ReflectionTestUtils.setField(other, "id", 5L);
        Invoice chosen = invoice(InvoiceStatus.QUEUED, null);
        ReflectionTestUtils.setField(chosen, "id", 17L);
        java.util.Set<Long> only = java.util.Collections.singleton(17L);

        assertThat(IntakeBoardService.blockedReason(other, null, true, null, only, NOW))
                .contains("limited to invoice(s) [17]").contains("stays queued");
        assertThat(IntakeBoardService.blockedReason(chosen, null, true, null, only, NOW)).isEqualTo("Waiting in the extraction queue.");
        // A worker that is off still says so first.
        assertThat(IntakeBoardService.blockedReason(other, null, false, "off", only, NOW)).isEqualTo("Waiting for extraction: off");
    }

    @Test
    void readyInvoicesOnlyWaitWhenNobodyIsAssigned() {
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.NEEDS_REVIEW, null), null, false, "off", NOW))
                .isEqualTo("Extraction finished; waiting for a reviewer to be assigned.");
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.NEEDS_REVIEW, 7L), null, false, "off", NOW)).isNull();
        assertThat(IntakeBoardService.blockedReason(invoice(InvoiceStatus.PROCESSING, null), null, true, null, NOW)).isNull();
    }
}
