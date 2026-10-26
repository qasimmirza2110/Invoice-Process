package com.actify.invoice.review;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The vendor-scoped business duplicate rule, without a database (the query side is covered by the pgtests). */
class DuplicateInvoiceFinderTest {

    private final InvoiceRepository repository = mock(InvoiceRepository.class);
    private final DuplicateInvoiceFinder finder = new DuplicateInvoiceFinder(repository);

    private static Invoice invoice(long id, Long owner, String number, String sellerName, String sellerTaxId) {
        Invoice i = new Invoice();
        ReflectionTestUtils.setField(i, "id", id);
        ReflectionTestUtils.setField(i, "ownerUserId", owner);
        i.setStatus(InvoiceStatus.NEEDS_REVIEW);
        i.setInvoiceNumber(number);
        i.setSellerName(sellerName);
        i.setSellerTaxId(sellerTaxId);
        ReflectionTestUtils.setField(i, "createdAt", LocalDateTime.of(2026, 10, 20, 9, 30));
        return i;
    }

    @Test
    void numbersAndSellerNamesAreComparedAfterNormalization() {
        assertThat(DuplicateInvoiceFinder.normalizeInvoiceNumber(" inv-2026/0043 ")).isEqualTo("INV20260043");
        assertThat(DuplicateInvoiceFinder.normalizeInvoiceNumber(" - ")).isNull();
        assertThat(DuplicateInvoiceFinder.normalizeSellerName("  Demo Furniture   Supplies, Pvt. Ltd.  "))
                .isEqualTo("demo furniture supplies pvt ltd");
        assertThat(DuplicateInvoiceFinder.sameSeller(null, "ACME  Ltd", null, "acme ltd.")).isTrue();
        // When both have a tax id, the tax id decides - even if the names differ slightly.
        assertThat(DuplicateInvoiceFinder.sameSeller("29abcde1234f1z5", "Acme", "29ABCDE1234F1Z5", "Acme Traders")).isTrue();
        assertThat(DuplicateInvoiceFinder.sameSeller("29ABCDE1234F1Z5", "Acme", "27PQRSX5678K1Z2", "Acme")).isFalse();
    }

    @Test
    void theSameVendorsSameSellerAndNumberIsADuplicateEvenWithDifferentFormatting() {
        Invoice earlier = invoice(12, 3L, "INV-0043", "Demo Furniture Supplies Pvt Ltd", null);
        when(repository.findDuplicateCandidates(3L, 20L)).thenReturn(Collections.singletonList(earlier));
        Invoice now = invoice(20, 3L, "inv 0043", "DEMO FURNITURE SUPPLIES PVT. LTD.", null);

        assertThat(finder.findSameVendorDuplicate(now, now.getInvoiceNumber(), now.getSellerTaxId(), now.getSellerName()))
                .isSameAs(earlier);
        assertThat(DuplicateInvoiceFinder.describe(earlier))
                .isEqualTo("You already uploaded invoice INV-0043 from Demo Furniture Supplies Pvt Ltd (invoice 12, uploaded 2026-10-20).");
    }

    @Test
    void theSameNumberFromAnotherSellerIsNotADuplicate() {
        when(repository.findDuplicateCandidates(3L, 20L)).thenReturn(Collections.singletonList(invoice(12, 3L, "1001", "Seller A", null)));
        Invoice now = invoice(20, 3L, "1001", "Seller B", null);
        assertThat(finder.findSameVendorDuplicate(now, "1001", null, "Seller B")).isNull();
    }

    @Test
    void missingNumberOrSellerIdentityNeverClaimsADuplicate() {
        Invoice noNumber = invoice(20, 3L, null, "Seller A", null);
        assertThat(finder.findSameVendorDuplicate(noNumber, null, null, "Seller A")).isNull();
        Invoice noSeller = invoice(21, 3L, "1001", null, null);
        assertThat(finder.findSameVendorDuplicate(noSeller, "1001", null, null)).isNull();
        verify(repository, never()).findDuplicateCandidates(anyLong(), anyLong());
    }

    @Test
    void onlyTheSameVendorsInvoicesAreEverQueriedAndLegacyRowsAreSkipped() {
        when(repository.findDuplicateCandidates(3L, 20L)).thenReturn(Arrays.<Invoice>asList());
        Invoice mine = invoice(20, 3L, "1001", "Seller A", null);
        assertThat(finder.findSameVendorDuplicate(mine, "1001", null, "Seller A")).isNull();
        verify(repository).findDuplicateCandidates(3L, 20L); // owner 3 only - another vendor's rows are never loaded

        Invoice legacy = invoice(30, null, "1001", "Seller A", null);
        assertThat(finder.findSameVendorDuplicate(legacy, "1001", null, "Seller A")).isNull();
    }
}
