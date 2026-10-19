package com.actify.invoice.workflow;

import com.actify.invoice.entity.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.actify.invoice.entity.InvoiceStatus.APPROVED;
import static com.actify.invoice.entity.InvoiceStatus.CREATED;
import static com.actify.invoice.entity.InvoiceStatus.EXTRACTED;
import static com.actify.invoice.entity.InvoiceStatus.FAILED;
import static com.actify.invoice.entity.InvoiceStatus.NEEDS_REVIEW;
import static com.actify.invoice.entity.InvoiceStatus.PENDING_APPROVAL;
import static com.actify.invoice.entity.InvoiceStatus.PROCESSING;
import static com.actify.invoice.entity.InvoiceStatus.QUEUED;
import static com.actify.invoice.entity.InvoiceStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTransitionsTest {

    @Test
    void everyStatusNameFitsTheTwentyCharacterColumn() {
        for (InvoiceStatus status : InvoiceStatus.values()) {
            assertThat(status.name().length()).as(status.name()).isLessThanOrEqualTo(20);
        }
    }

    @Test
    void exactlyTheDocumentedMovesAreAllowed() {
        expect(null, QUEUED);
        expect(QUEUED, PROCESSING);
        expect(PROCESSING, QUEUED, NEEDS_REVIEW, FAILED);
        expect(NEEDS_REVIEW, PENDING_APPROVAL);
        expect(PENDING_APPROVAL, NEEDS_REVIEW, APPROVED, REJECTED);
        expect(APPROVED, NEEDS_REVIEW);
        expect(REJECTED, NEEDS_REVIEW);
        expect(FAILED, QUEUED);
        // Legacy statuses are outside the workflow: nothing may move them.
        expect(EXTRACTED);
        expect(CREATED);
    }

    private void expect(InvoiceStatus from, InvoiceStatus... allowedTargets) {
        Set<InvoiceStatus> allowed = allowedTargets.length == 0 ? EnumSet.noneOf(InvoiceStatus.class) : EnumSet.of(allowedTargets[0], allowedTargets);
        for (InvoiceStatus to : InvoiceStatus.values()) {
            assertThat(WorkflowTransitions.isAllowed(from, to)).as(from + " -> " + to).isEqualTo(allowed.contains(to));
        }
    }
}
