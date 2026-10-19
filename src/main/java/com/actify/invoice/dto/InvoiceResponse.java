package com.actify.invoice.dto;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.actify.invoice.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the API returns. We never return the JPA entity directly, and the raw LLM
 * response stays in the database only (it is for debugging, not for API clients).
 */
public class InvoiceResponse {

    private Long id;
    private InvoiceStatus status;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String sellerName;
    private String sellerTaxId;
    private String buyerName;
    private String buyerTaxId;
    private String currency;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;      // null when the invoice has no discount (or is an older row)
    private BigDecimal shippingAmount;      // null when the invoice has no shipping charge (or is an older row)
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String sourceFileName;
    private Long sourceFileSize;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<InvoiceItemDto> items = new ArrayList<>();
    // Always present as an object; its fields are null when nothing was reported (e.g. older invoices).
    private TokenUsage tokenUsage;
    // Latest stored version number. 0 = no version history yet (FAILED uploads, invoices from before
    // versioning). null only where the caller did not look it up.
    private Integer currentVersion;
    // The stored original PDF; null for invoices created before PDFs were stored.
    private SourceDocumentDto sourceDocument;
    // Batch-workflow facts (owner, batch, category, reviewer, submitted version). Added in one nested object so the
    // existing top-level fields keep exactly their previous meaning.
    private WorkflowInfo workflow;

    public static InvoiceResponse fromEntity(Invoice invoice) {
        return fromEntity(invoice, null);
    }

    public static InvoiceResponse fromEntity(Invoice invoice, Integer currentVersion) {
        InvoiceResponse response = new InvoiceResponse();
        response.id = invoice.getId();
        response.status = invoice.getStatus();
        response.invoiceNumber = invoice.getInvoiceNumber();
        response.invoiceDate = invoice.getInvoiceDate();
        response.sellerName = invoice.getSellerName();
        response.sellerTaxId = invoice.getSellerTaxId();
        response.buyerName = invoice.getBuyerName();
        response.buyerTaxId = invoice.getBuyerTaxId();
        response.currency = invoice.getCurrency();
        response.subtotal = invoice.getSubtotal();
        response.discountAmount = invoice.getDiscountAmount();
        response.shippingAmount = invoice.getShippingAmount();
        response.taxAmount = invoice.getTaxAmount();
        response.totalAmount = invoice.getTotalAmount();
        response.sourceFileName = invoice.getSourceFileName();
        response.sourceFileSize = invoice.getSourceFileSize();
        response.failureReason = invoice.getFailureReason();
        response.createdAt = invoice.getCreatedAt();
        response.updatedAt = invoice.getUpdatedAt();

        for (InvoiceItem item : invoice.getItems()) {
            response.items.add(new InvoiceItemDto(item.getDescription(), item.getQuantity(),
                    item.getUnitPrice(), item.getTaxRate(), item.getLineTotal()));
        }

        // Read back from the stored columns, so POST and a later GET return identical values.
        response.tokenUsage = new TokenUsage(invoice.getGeminiModelId(), invoice.getGeminiModelVersion(),
                invoice.getPromptTokenCount(), invoice.getCandidatesTokenCount(), invoice.getThoughtsTokenCount(),
                invoice.getTotalTokenCount(), invoice.getExtractionDurationMs());
        response.currentVersion = currentVersion;
        response.sourceDocument = SourceDocumentDto.fromEntity(invoice.getSourceDocument());
        response.workflow = new WorkflowInfo(invoice);
        return response;
    }

    public Long getId() { return id; }
    public InvoiceStatus getStatus() { return status; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public String getSellerName() { return sellerName; }
    public String getSellerTaxId() { return sellerTaxId; }
    public String getBuyerName() { return buyerName; }
    public String getBuyerTaxId() { return buyerTaxId; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getShippingAmount() { return shippingAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getSourceFileName() { return sourceFileName; }
    public Long getSourceFileSize() { return sourceFileSize; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<InvoiceItemDto> getItems() { return items; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public Integer getCurrentVersion() { return currentVersion; }
    public SourceDocumentDto getSourceDocument() { return sourceDocument; }
    public WorkflowInfo getWorkflow() { return workflow; }

    /** legacy = created before the batch workflow: no owner, ADMIN-only, never enrolled automatically. */
    public static class WorkflowInfo {
        private final boolean legacy;
        private final Long ownerUserId;
        private final Long batchId;
        private final String categoryCode;
        private final Long assignedReviewerId;
        private final Integer submittedVersion;

        WorkflowInfo(Invoice invoice) {
            this.legacy = invoice.isLegacy();
            this.ownerUserId = invoice.getOwnerUserId();
            this.batchId = invoice.getBatchId();
            this.categoryCode = invoice.getCategoryCode();
            this.assignedReviewerId = invoice.getAssignedReviewerId();
            this.submittedVersion = invoice.getSubmittedVersion();
        }

        public boolean isLegacy() { return legacy; }
        public Long getOwnerUserId() { return ownerUserId; }
        public Long getBatchId() { return batchId; }
        public String getCategoryCode() { return categoryCode; }
        public Long getAssignedReviewerId() { return assignedReviewerId; }
        public Integer getSubmittedVersion() { return submittedVersion; }
    }
}
