package com.actify.invoice.service;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceItemUpdate;
import com.actify.invoice.dto.InvoiceSaveResponse;
import com.actify.invoice.dto.InvoiceUpdateRequest;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.VersionChangeSource;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.InvoiceNotEditableException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.exception.VersionConflictException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.validation.InvoiceExtractionValidator;
import com.actify.invoice.versioning.FieldChange;
import com.actify.invoice.versioning.InvoiceSnapshot;
import com.actify.invoice.versioning.InvoiceSnapshotMapper;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business logic of a correction, without a database. Real validator and snapshot mapper; repository/writer mocked. */
@ExtendWith(MockitoExtension.class)
class InvoiceEditServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceVersionWriter versionWriter;

    private InvoiceEditService service;
    private final InvoiceSnapshotMapper mapper = new InvoiceSnapshotMapper(new ObjectMapper());

    @BeforeEach
    void setUp() {
        service = new InvoiceEditService(invoiceRepository, new InvoiceExtractionValidator(), mapper, versionWriter);
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
    }

    private Invoice stored() {
        Invoice invoice = new Invoice();
        invoice.setId(5L);
        invoice.setStatus(InvoiceStatus.EXTRACTED);
        invoice.setInvoiceNumber("INV-1");
        invoice.setInvoiceDate(LocalDate.of(2026, 10, 6));
        invoice.setSellerName("Seller");
        invoice.setCurrency("INR");
        invoice.setSubtotal(new BigDecimal("100.00"));
        invoice.setTaxAmount(new BigDecimal("18.00"));
        invoice.setTotalAmount(new BigDecimal("118.00"));
        InvoiceItem item = new InvoiceItem();
        item.setId(1L);
        item.setDescription("Thing");
        item.setQuantity(new BigDecimal("1.000"));
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setTaxRate(new BigDecimal("18.000"));
        item.setLineTotal(new BigDecimal("100.00"));
        invoice.addItem(item);
        return invoice;
    }

    /** The same data as stored(), as the browser would send it. */
    private InvoiceUpdateRequest request(int baseVersion) {
        InvoiceUpdateRequest r = new InvoiceUpdateRequest();
        r.setBaseVersion(baseVersion);
        r.setInvoiceNumber("INV-1");
        r.setInvoiceDate("2026-10-06");
        r.setSellerName("Seller");
        r.setCurrency("INR");
        r.setSubtotal(new BigDecimal("100.00"));
        r.setTaxAmount(new BigDecimal("18.00"));
        r.setTotalAmount(new BigDecimal("118.00"));
        r.setItems(new ArrayList<>(Arrays.asList(new InvoiceItemUpdate("Thing", new BigDecimal("1"),
                new BigDecimal("100.00"), new BigDecimal("18"), new BigDecimal("100.00")))));
        return r;
    }

    private void storedAtVersion(Invoice invoice, int version) {
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));
        when(versionWriter.currentVersion(5L)).thenReturn(version);
    }

    @Test
    void correctionCreatesTheNextVersionWithChangedFieldsAndKeepsLeadingZeros() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 1);
        InvoiceUpdateRequest r = request(1);
        r.setInvoiceNumber("000000005");
        r.setSellerTaxId("6000000001");
        r.setChangeReason("  typo  ");

        InvoiceSaveResponse response = service.saveCorrection(5L, r);

        assertThat(response.isChanged()).isTrue();
        assertThat(response.getInvoice().getCurrentVersion()).isEqualTo(2);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("000000005");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldChange>> changes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<InvoiceSnapshot> snapshot = ArgumentCaptor.forClass(InvoiceSnapshot.class);
        verify(versionWriter).append(eq(invoice), eq(2), eq(VersionChangeSource.MANUAL_EDIT), eq("typo"),
                snapshot.capture(), changes.capture());
        assertThat(changes.getValue()).extracting(FieldChange::getField).containsExactly("invoiceNumber", "sellerTaxId");
        assertThat(snapshot.getValue().getInvoiceNumber()).isEqualTo("000000005");
        assertThat(snapshot.getValue().getSellerTaxId()).isEqualTo("6000000001");
        assertThat(snapshot.getValue().getItems()).hasSize(1);
    }

    @Test
    void identicalDataCreatesNoVersion() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 3);
        InvoiceUpdateRequest r = request(3);
        r.setSubtotal(new BigDecimal("100"));      // same value, other scale
        r.setSellerName(" Seller ");               // same after trimming

        InvoiceSaveResponse response = service.saveCorrection(5L, r);

        assertThat(response.isChanged()).isFalse();
        assertThat(response.getInvoice().getCurrentVersion()).isEqualTo(3);
        verify(versionWriter, never()).append(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void staleBaseVersionIsRejectedBeforeAnythingChanges() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 4);
        InvoiceUpdateRequest r = request(3);
        r.setSellerName("Late edit");

        assertThatThrownBy(() -> service.saveCorrection(5L, r))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("Reload the latest version");
        assertThat(invoice.getSellerName()).isEqualTo("Seller");
        verify(versionWriter, never()).append(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void unknownFieldsAndMissingBaseVersionAreRejectedBeforeTheLockIsTaken() {
        InvoiceUpdateRequest r = request(1);
        r.setBaseVersion(null);
        r.addUnknownField("id", 99);
        r.addUnknownField("status", "FAILED");
        r.getItems().get(0).addUnknownField("invoiceId", 1);

        assertThatThrownBy(() -> service.saveCorrection(5L, r))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).getFieldErrors())
                        .extracting(FieldErrorDto::getField)
                        .containsExactly("id", "status", "baseVersion", "items[0].invoiceId"));
        verifyNoInteractions(invoiceRepository, versionWriter);
    }

    @Test
    void missingItemsListIsRejected() {
        InvoiceUpdateRequest r = request(1);
        r.setItems(null);
        assertThatThrownBy(() -> service.saveCorrection(5L, r)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("items is required");
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void businessRulesAndDecimalLimitsGiveFieldErrors() {
        storedAtVersion(stored(), 1);
        InvoiceUpdateRequest r = request(1);
        r.setTotalAmount(new BigDecimal("119.00"));                  // arithmetic
        r.getItems().get(0).setUnitPrice(new BigDecimal("100.005")); // 3 decimals for numeric(19,2)
        r.getItems().get(0).setTaxRate(new BigDecimal("12345"));      // too big for numeric(7,3)
        r.setCurrency("inr");                                         // not auto-corrected

        assertThatThrownBy(() -> service.saveCorrection(5L, r))
                .isInstanceOf(InvoiceValidationException.class)
                .satisfies(e -> assertThat(((InvoiceValidationException) e).getFieldErrors())
                        .extracting(FieldErrorDto::getField)
                        .contains("totalAmount", "items[0].unitPrice", "items[0].taxRate", "currency"));
        verify(versionWriter, never()).append(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void trailingZerosBeyondTheScaleAreAccepted() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 1);
        InvoiceUpdateRequest r = request(1);
        r.getItems().get(0).setDescription("Thing v2");
        r.getItems().get(0).setUnitPrice(new BigDecimal("100.000"));   // still exactly 100.00

        assertThat(service.saveCorrection(5L, r).isChanged()).isTrue();
        assertThat(invoice.getItems().get(0).getUnitPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void legacyInvoiceGetsABaselineBeforeTheCorrection() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 0);
        InvoiceUpdateRequest r = request(0);
        r.setSellerName("Seller (fixed)");

        InvoiceSaveResponse response = service.saveCorrection(5L, r);

        assertThat(response.getInvoice().getCurrentVersion()).isEqualTo(2);
        InOrder order = inOrder(versionWriter);
        ArgumentCaptor<InvoiceSnapshot> baseline = ArgumentCaptor.forClass(InvoiceSnapshot.class);
        order.verify(versionWriter).append(eq(invoice), eq(1), eq(VersionChangeSource.LEGACY_BASELINE),
                eq(InvoiceEditService.LEGACY_BASELINE_NOTE), baseline.capture(), isNull());
        order.verify(versionWriter).append(eq(invoice), eq(2), eq(VersionChangeSource.MANUAL_EDIT), isNull(), any(), any());
        assertThat(baseline.getValue().getSellerName()).isEqualTo("Seller");   // the pre-edit value
        assertThat(InvoiceEditService.LEGACY_BASELINE_NOTE).contains("NOT a verified original");
    }

    @Test
    void lineItemsAreUpdatedInPlaceAppendedAndRemovedByPosition() {
        Invoice invoice = stored();
        storedAtVersion(invoice, 1);
        InvoiceUpdateRequest r = request(1);
        r.getItems().add(new InvoiceItemUpdate("Extra", new BigDecimal("1"), new BigDecimal("10.00"), null,
                new BigDecimal("10.00")));
        r.setSubtotal(new BigDecimal("110.00"));
        r.setTotalAmount(new BigDecimal("128.00"));

        service.saveCorrection(5L, r);

        assertThat(invoice.getItems()).hasSize(2);
        assertThat(invoice.getItems().get(0).getId()).isEqualTo(1L);     // same row, kept
        assertThat(invoice.getItems().get(1).getInvoice()).isSameAs(invoice);

        storedAtVersion(invoice, 2);
        InvoiceUpdateRequest shorter = request(2);
        shorter.getItems().get(0).setDescription("Only one left");
        service.saveCorrection(5L, shorter);
        assertThat(invoice.getItems()).hasSize(1);
        assertThat(invoice.getItems().get(0).getDescription()).isEqualTo("Only one left");
    }

    @Test
    void onlyExtractedInvoicesCanBeEdited() {
        Invoice failed = stored();
        failed.setStatus(InvoiceStatus.FAILED);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(failed));
        assertThatThrownBy(() -> service.saveCorrection(5L, request(0))).isInstanceOf(InvoiceNotEditableException.class);

        when(invoiceRepository.findByIdForUpdate(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveCorrection(6L, request(0))).isInstanceOf(InvoiceNotFoundException.class);
    }
}
