package com.actify.invoice.service;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.InvoiceItemUpdate;
import com.actify.invoice.dto.InvoiceResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Saves a user's correction of an extracted invoice as a new version.
 *
 * Everything happens in ONE transaction: lock the invoice row (SELECT ... FOR UPDATE), check the
 * base version, validate, update invoices/invoice_items, append the version snapshot. Any failure
 * rolls all of it back, so the current data and the history can never disagree.
 *
 * A correction never calls the LLM again and never touches the stored PDF.
 */
@Service
public class InvoiceEditService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEditService.class);

    static final String LEGACY_BASELINE_NOTE = "Pre-edit state captured automatically on the first correction "
            + "of an invoice created before version history existed. NOT a verified original LLM extraction.";

    static final int MAX_ITEMS = 200;
    static final int MAX_REASON_LENGTH = 500;

    // numeric(precision, scale) of the target columns -> max digits before the decimal point.
    private static final int MONEY_INTEGER_DIGITS = 17;      // numeric(19,2)
    private static final int QUANTITY_INTEGER_DIGITS = 16;   // numeric(19,3)
    private static final int TAX_RATE_INTEGER_DIGITS = 4;    // numeric(7,3)

    private final InvoiceRepository invoiceRepository;
    private final InvoiceExtractionValidator validator;
    private final InvoiceSnapshotMapper snapshotMapper;
    private final InvoiceVersionWriter versionWriter;

    @PersistenceContext
    private EntityManager entityManager;

    public InvoiceEditService(InvoiceRepository invoiceRepository, InvoiceExtractionValidator validator,
                              InvoiceSnapshotMapper snapshotMapper, InvoiceVersionWriter versionWriter) {
        this.invoiceRepository = invoiceRepository;
        this.validator = validator;
        this.snapshotMapper = snapshotMapper;
        this.versionWriter = versionWriter;
    }

    @Transactional
    public InvoiceSaveResponse saveCorrection(Long invoiceId, InvoiceUpdateRequest request) {
        // 1. Request shape first - no lock needed to reject a malformed request.
        checkRequestShape(request);

        // 2. Lock the invoice row; a second save of the same invoice waits here until this one commits.
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        // EXTRACTED = legacy flow; NEEDS_REVIEW = workflow invoice being reviewed. WHO may edit is decided by
        // InvoiceAccessPolicy.requireCanCorrect before this service is called.
        if (invoice.getStatus() != InvoiceStatus.EXTRACTED && invoice.getStatus() != InvoiceStatus.NEEDS_REVIEW) {
            throw new InvoiceNotEditableException(invoiceId, invoice.getStatus());
        }

        // 3. Stale edit? Checked under the lock, before anything is changed.
        int currentVersion = versionWriter.currentVersion(invoiceId);
        if (request.getBaseVersion() != currentVersion) {
            throw new VersionConflictException(currentVersion, request.getBaseVersion());
        }

        // 4. Same business rules as the extraction, plus the database's decimal limits.
        InvoiceExtractionResult values = normalize(request);
        List<FieldErrorDto> problems = new ArrayList<>(validator.findProblems(values));
        checkDecimalLimits(values, problems);
        if (!problems.isEmpty()) {
            throw new InvoiceValidationException(joinMessages(problems), problems);
        }

        // 5. Nothing changed -> no new version.
        InvoiceSnapshot before = snapshotMapper.fromEntity(invoice);
        InvoiceSnapshot proposed = snapshotMapper.fromValues(values);
        if (snapshotMapper.diff(before, proposed).isEmpty()) {
            log.info("Invoice {} correction had no changes; still version {}", invoiceId, currentVersion);
            return new InvoiceSaveResponse(false, InvoiceResponse.fromEntity(invoice, currentVersion));
        }

        // 6. An invoice from before version history: keep its pre-edit state first, honestly labelled.
        if (currentVersion == 0) {
            versionWriter.append(invoice, 1, VersionChangeSource.LEGACY_BASELINE, LEGACY_BASELINE_NOTE, before, null);
            currentVersion = 1;
        }

        // 7. Update the current working copy, then re-read what PostgreSQL stored.
        applyTo(invoice, values);
        entityManager.flush();
        entityManager.refresh(invoice);
        InvoiceSnapshot after = snapshotMapper.fromEntity(invoice);

        // 8. Append the new complete snapshot + what changed.
        int newVersion = currentVersion + 1;
        List<FieldChange> changes = snapshotMapper.diff(before, after);
        versionWriter.append(invoice, newVersion, VersionChangeSource.MANUAL_EDIT,
                blankToNull(request.getChangeReason()), after, changes);

        log.info("Invoice {} corrected: version {} saved with {} changed fields", invoiceId, newVersion, changes.size());
        return new InvoiceSaveResponse(true, InvoiceResponse.fromEntity(invoice, newVersion));
    }

    // ---------------------------------------------------------------- request checks

    private void checkRequestShape(InvoiceUpdateRequest request) {
        List<FieldErrorDto> errors = new ArrayList<>();
        if (request == null) {
            throw new InvalidRequestException("Request body is required", errors);
        }
        for (String name : request.getUnknownFields().keySet()) {
            errors.add(new FieldErrorDto(name, name + " is not an editable field"));
        }
        if (request.getBaseVersion() == null) {
            errors.add(new FieldErrorDto("baseVersion", "baseVersion is required (the version you started editing from)"));
        } else if (request.getBaseVersion() < 0) {
            errors.add(new FieldErrorDto("baseVersion", "baseVersion must not be negative"));
        }
        if (request.getChangeReason() != null && request.getChangeReason().length() > MAX_REASON_LENGTH) {
            errors.add(new FieldErrorDto("changeReason", "changeReason is longer than " + MAX_REASON_LENGTH + " characters"));
        }
        if (request.getItems() == null) {
            errors.add(new FieldErrorDto("items", "items is required (send the complete list; [] for no line items)"));
        } else {
            if (request.getItems().size() > MAX_ITEMS) {
                errors.add(new FieldErrorDto("items", "items has more than " + MAX_ITEMS + " entries"));
            }
            for (int i = 0; i < request.getItems().size(); i++) {
                InvoiceItemUpdate item = request.getItems().get(i);
                if (item == null) {
                    errors.add(new FieldErrorDto("items[" + i + "]", "items[" + i + "] must be an object"));
                    continue;
                }
                for (Map.Entry<String, Object> unknown : item.getUnknownFields().entrySet()) {
                    String field = "items[" + i + "]." + unknown.getKey();
                    errors.add(new FieldErrorDto(field, field + " is not an editable field"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(joinMessages(errors), errors);
        }
    }

    /** Trim text (blank -> null, like the extraction does); keep identifiers as strings so leading zeros survive. */
    private InvoiceExtractionResult normalize(InvoiceUpdateRequest request) {
        InvoiceExtractionResult v = new InvoiceExtractionResult();
        v.setInvoiceNumber(blankToNull(request.getInvoiceNumber()));
        v.setInvoiceDate(blankToNull(request.getInvoiceDate()));
        v.setSellerName(blankToNull(request.getSellerName()));
        v.setSellerTaxId(blankToNull(request.getSellerTaxId()));
        v.setBuyerName(blankToNull(request.getBuyerName()));
        v.setBuyerTaxId(blankToNull(request.getBuyerTaxId()));
        v.setCurrency(blankToNull(request.getCurrency()));
        v.setSubtotal(request.getSubtotal());
        v.setDiscountAmount(request.getDiscountAmount());
        v.setShippingAmount(request.getShippingAmount());
        v.setTaxAmount(request.getTaxAmount());
        v.setTotalAmount(request.getTotalAmount());
        List<InvoiceItemDto> items = new ArrayList<>();
        for (InvoiceItemUpdate item : request.getItems()) {
            items.add(new InvoiceItemDto(blankToNull(item.getDescription()), item.getQuantity(), item.getUnitPrice(),
                    item.getTaxRate(), item.getLineTotal()));
        }
        v.setItems(items);
        return v;
    }

    /**
     * PostgreSQL would silently round 10.555 into numeric(19,2). A human correction must be stored
     * exactly as typed, so too many decimals (or digits) are rejected instead of rounded.
     */
    private void checkDecimalLimits(InvoiceExtractionResult v, List<FieldErrorDto> errors) {
        checkDecimal("subtotal", v.getSubtotal(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        checkDecimal("discountAmount", v.getDiscountAmount(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        checkDecimal("shippingAmount", v.getShippingAmount(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        checkDecimal("taxAmount", v.getTaxAmount(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        checkDecimal("totalAmount", v.getTotalAmount(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        for (int i = 0; i < v.getItems().size(); i++) {
            InvoiceItemDto item = v.getItems().get(i);
            String p = "items[" + i + "].";
            checkDecimal(p + "quantity", item.getQuantity(), InvoiceSnapshotMapper.QUANTITY_SCALE, QUANTITY_INTEGER_DIGITS, errors);
            checkDecimal(p + "unitPrice", item.getUnitPrice(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
            checkDecimal(p + "taxRate", item.getTaxRate(), InvoiceSnapshotMapper.TAX_RATE_SCALE, TAX_RATE_INTEGER_DIGITS, errors);
            checkDecimal(p + "lineTotal", item.getLineTotal(), InvoiceSnapshotMapper.MONEY_SCALE, MONEY_INTEGER_DIGITS, errors);
        }
    }

    private void checkDecimal(String field, BigDecimal value, int maxScale, int maxIntegerDigits, List<FieldErrorDto> errors) {
        if (value == null) {
            return;
        }
        BigDecimal stripped = value.stripTrailingZeros();   // "10.500" has 2 real decimals, not 3
        if (stripped.scale() > maxScale) {
            errors.add(new FieldErrorDto(field, field + " has more than " + maxScale + " decimal places"));
        }
        int integerDigits = stripped.precision() - stripped.scale();
        if (integerDigits > maxIntegerDigits) {
            errors.add(new FieldErrorDto(field, field + " is too large"));
        }
    }

    // ---------------------------------------------------------------- applying the change

    /**
     * Updates the managed entity. Line items are matched by position: existing rows are updated in
     * place, extra rows are appended (new, higher ids keep the order) and surplus rows are removed
     * (orphanRemoval). Values have passed checkDecimal, so setScale only pads zeros.
     */
    private void applyTo(Invoice invoice, InvoiceExtractionResult v) {
        invoice.setInvoiceNumber(v.getInvoiceNumber());
        invoice.setInvoiceDate(v.getInvoiceDate() == null ? null : LocalDate.parse(v.getInvoiceDate()));
        invoice.setSellerName(v.getSellerName());
        invoice.setSellerTaxId(v.getSellerTaxId());
        invoice.setBuyerName(v.getBuyerName());
        invoice.setBuyerTaxId(v.getBuyerTaxId());
        invoice.setCurrency(v.getCurrency());
        invoice.setSubtotal(scaled(v.getSubtotal(), InvoiceSnapshotMapper.MONEY_SCALE));
        invoice.setDiscountAmount(scaled(v.getDiscountAmount(), InvoiceSnapshotMapper.MONEY_SCALE));
        invoice.setShippingAmount(scaled(v.getShippingAmount(), InvoiceSnapshotMapper.MONEY_SCALE));
        invoice.setTaxAmount(scaled(v.getTaxAmount(), InvoiceSnapshotMapper.MONEY_SCALE));
        invoice.setTotalAmount(scaled(v.getTotalAmount(), InvoiceSnapshotMapper.MONEY_SCALE));

        List<InvoiceItem> items = invoice.getItems();
        List<InvoiceItemDto> wanted = v.getItems();
        for (int i = 0; i < wanted.size(); i++) {
            InvoiceItem item;
            if (i < items.size()) {
                item = items.get(i);
            } else {
                item = new InvoiceItem();
                invoice.addItem(item);
            }
            InvoiceItemDto dto = wanted.get(i);
            item.setDescription(dto.getDescription());
            item.setQuantity(scaled(dto.getQuantity(), InvoiceSnapshotMapper.QUANTITY_SCALE));
            item.setUnitPrice(scaled(dto.getUnitPrice(), InvoiceSnapshotMapper.MONEY_SCALE));
            item.setTaxRate(scaled(dto.getTaxRate(), InvoiceSnapshotMapper.TAX_RATE_SCALE));
            item.setLineTotal(scaled(dto.getLineTotal(), InvoiceSnapshotMapper.MONEY_SCALE));
        }
        while (items.size() > wanted.size()) {
            items.remove(items.size() - 1);
        }
    }

    private BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String joinMessages(List<FieldErrorDto> errors) {
        List<String> messages = new ArrayList<>();
        for (FieldErrorDto error : errors) {
            messages.add(error.getMessage());
        }
        return String.join("; ", messages);
    }
}
