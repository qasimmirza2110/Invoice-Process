package com.actify.invoice.review;

import com.actify.invoice.dto.WorkflowDtos.AssignmentResult;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.ReviewerCategoryRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.security.TestLogins;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import com.actify.invoice.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Assignment rules: allowed before extraction (QUEUED / PROCESSING), "Assign" never silently replaces an existing
 * reviewer, "Reassign" does and leaves an audit event, every invoice gets its own honest result.
 */
class ReviewAssignmentServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ReviewerCategoryRepository reviewerCategories = mock(ReviewerCategoryRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final WorkflowService workflow = mock(WorkflowService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ReviewAssignmentService service =
            new ReviewAssignmentService(users, null, reviewerCategories, invoices, null, workflow, currentUser);

    private final AppUser vendor = TestLogins.user(2, Role.VENDOR);
    private final AppUser alex = TestLogins.user(7, Role.REVIEWER);
    private final AppUser lee = TestLogins.user(8, Role.REVIEWER);

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(1L, "admin@example.test", "Admin", Role.ADMIN));
        when(users.findById(7L)).thenReturn(Optional.of(alex));
        when(users.findById(8L)).thenReturn(Optional.of(lee));
        when(reviewerCategories.reviewerHasCategory(anyLong(), eq("FURNITURE"))).thenReturn(true);
    }

    private Invoice invoice(long id, InvoiceStatus status, String category, AppUser reviewer) {
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", id);
        invoice.setOwner(vendor);
        invoice.setStatus(status);
        invoice.setCategoryCode(category);
        invoice.setAssignedReviewer(reviewer);
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void aQueuedInvoiceCanBeAssignedBeforeExtractionAndTheChangeIsAudited() {
        Invoice queued = invoice(41, InvoiceStatus.QUEUED, "FURNITURE", null);

        List<AssignmentResult> results = service.assign(Collections.singletonList(41L), 7L, false);

        assertThat(results).hasSize(1);
        AssignmentResult r = results.get(0);
        assertThat(r.isAssigned()).isTrue();
        assertThat(r.getMessage()).isEqualTo("Assigned.");
        assertThat(r.getStatus()).isEqualTo(InvoiceStatus.QUEUED); // still waiting for extraction, but now assigned
        assertThat(r.getReviewerId()).isEqualTo(7L);
        assertThat(r.getReviewerName()).isEqualTo("REVIEWER 7");
        assertThat(queued.getAssignedReviewerId()).isEqualTo(7L);
        verify(workflow).recordAssignment(queued, 1L, "Assigned to REVIEWER 7");
    }

    @Test
    void assignNeverSilentlyReplacesAnExistingReviewer() {
        Invoice taken = invoice(42, InvoiceStatus.NEEDS_REVIEW, "FURNITURE", alex);

        AssignmentResult r = service.assign(Collections.singletonList(42L), 8L, false).get(0);

        assertThat(r.isAssigned()).isFalse();
        assertThat(r.getMessage()).contains("Already assigned to REVIEWER 7").contains("Reassign");
        assertThat(r.getReviewerId()).isEqualTo(7L);
        assertThat(taken.getAssignedReviewerId()).isEqualTo(7L);
        verify(workflow, never()).recordAssignment(any(), any(), anyString());
    }

    @Test
    void reassignIsExplicitAndAudited() {
        Invoice taken = invoice(43, InvoiceStatus.NEEDS_REVIEW, "FURNITURE", alex);

        AssignmentResult r = service.assign(Collections.singletonList(43L), 8L, true).get(0);

        assertThat(r.isAssigned()).isTrue();
        assertThat(r.getMessage()).isEqualTo("Reassigned.");
        assertThat(taken.getAssignedReviewerId()).isEqualTo(8L);
        verify(workflow).recordAssignment(taken, 1L, "Reassigned from REVIEWER 7 to REVIEWER 8");
    }

    @Test
    void unassignNeedsTheReassignFlag() {
        Invoice taken = invoice(44, InvoiceStatus.QUEUED, "FURNITURE", alex);

        assertThat(service.assign(Collections.singletonList(44L), null, false).get(0).isAssigned()).isFalse();
        assertThat(taken.getAssignedReviewerId()).isEqualTo(7L);

        AssignmentResult r = service.assign(Collections.singletonList(44L), null, true).get(0);
        assertThat(r.isAssigned()).isTrue();
        assertThat(r.getMessage()).isEqualTo("Unassigned.");
        assertThat(taken.getAssignedReviewerId()).isNull();
        verify(workflow).recordAssignment(taken, 1L, "Reassigned from REVIEWER 7 to nobody");
    }

    @Test
    void assigningTheSameReviewerAgainIsANoOpWithoutAnEvent() {
        invoice(45, InvoiceStatus.NEEDS_REVIEW, "FURNITURE", alex);

        AssignmentResult r = service.assign(Collections.singletonList(45L), 7L, false).get(0);

        assertThat(r.isAssigned()).isTrue();
        assertThat(r.getMessage()).isEqualTo("Already assigned to REVIEWER 7.");
        verify(workflow, never()).recordAssignment(any(), any(), anyString());
    }

    @Test
    void aMixedSelectionGetsOneHonestResultPerInvoice() {
        invoice(51, InvoiceStatus.QUEUED, "FURNITURE", null);         // ok
        invoice(52, InvoiceStatus.NEEDS_REVIEW, null, null);           // no category
        invoice(53, InvoiceStatus.NEEDS_REVIEW, "IT", null);           // reviewer does not handle IT
        invoice(54, InvoiceStatus.APPROVED, "FURNITURE", null);        // decided: not assignable
        when(invoices.findByIdForUpdate(55L)).thenReturn(Optional.empty());

        List<AssignmentResult> results = service.assign(Arrays.asList(51L, 52L, 53L, 54L, 55L, 51L), 7L, false);

        assertThat(results).extracting(AssignmentResult::getInvoiceId).containsExactly(51L, 52L, 53L, 54L, 55L);
        assertThat(results).extracting(AssignmentResult::isAssigned).containsExactly(true, false, false, false, false);
        assertThat(results.get(1).getMessage()).contains("category first");
        assertThat(results.get(2).getMessage()).contains("does not handle category IT");
        assertThat(results.get(3).getMessage()).contains("APPROVED cannot be (re)assigned");
        assertThat(results.get(4).getMessage()).contains("No such workflow invoice");
    }
}
