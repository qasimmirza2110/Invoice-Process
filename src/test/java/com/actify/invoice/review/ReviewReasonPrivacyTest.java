package com.actify.invoice.review;

import com.actify.invoice.dto.WorkflowDtos.ReviewReasonResponse;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceReviewReason;
import com.actify.invoice.repository.InvoiceReviewReasonRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.user.Role;
import com.actify.invoice.validation.ReviewReasonCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DUPLICATE_FILE / DUPLICATE_INVOICE_NO are computed ONLY against the same vendor's own invoices (DuplicateInvoiceFinder),
 * so they describe the vendor's own uploads and the vendor may see them - which also lets the vendor understand why an
 * upload is a duplicate. Privacy across vendors is guaranteed where the reasons are computed, not by hiding them.
 */
class ReviewReasonPrivacyTest {

    private final InvoiceReviewReasonRepository reasons = mock(InvoiceReviewReasonRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ReviewService service = new ReviewService(null, reasons, null, null, null, null, currentUser, null, null);

    private void storedReasons() {
        Invoice invoice = mock(Invoice.class);
        when(reasons.findByInvoiceId(41L)).thenReturn(Arrays.asList(
                new InvoiceReviewReason(invoice, 1, ReviewReasonCode.TOTAL_MISMATCH, "totalAmount", "Total does not add up"),
                new InvoiceReviewReason(invoice, 1, ReviewReasonCode.DUPLICATE_INVOICE_NO, "invoiceNumber",
                        "You already uploaded invoice INV-1 from Seller (invoice 12, uploaded 2026-10-20).")));
    }

    private List<ReviewReasonResponse> as(Role role) {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(5L, "someone@example.test", "Someone", role));
        return service.reviewReasons(41L);
    }

    @Test
    void theVendorSeesTheirOwnDuplicateExplanation() {
        storedReasons();
        assertThat(as(Role.VENDOR)).extracting(ReviewReasonResponse::getCode).containsExactly("TOTAL_MISMATCH", "DUPLICATE_INVOICE_NO");
    }

    @Test
    void reviewersAndAdminsSeeTheSameReasons() {
        storedReasons();
        assertThat(as(Role.REVIEWER)).hasSize(2);
        assertThat(as(Role.ADMIN)).hasSize(2);
    }

    @Test
    void everyCodeIsVendorVisibleBecauseNoneIsComputedAcrossVendors() {
        assertThat(ReviewReasonCode.visibleToVendor()).hasSize(ReviewReasonCode.values().length);
    }
}
