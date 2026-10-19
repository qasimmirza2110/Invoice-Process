package com.actify.invoice.entity;

import com.actify.invoice.user.AppUser;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "seller_tax_id")
    private String sellerTaxId;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "buyer_tax_id")
    private String buyerTaxId;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "subtotal", precision = 19, scale = 2)
    private BigDecimal subtotal;

    // Nullable: invoices without these adjustments (and all older rows) simply have no value.
    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "shipping_amount", precision = 19, scale = 2)
    private BigDecimal shippingAmount;

    @Column(name = "tax_amount", precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "source_file_name")
    private String sourceFileName;

    @Column(name = "source_file_size")
    private Long sourceFileSize;

    // The stored original PDF. Nullable: invoices created before PDFs were stored have none,
    // and we never invent one for them.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id",
            foreignKey = @ForeignKey(name = "fk_invoices_source_document"))
    private InvoiceDocument sourceDocument;

    // Kept only for debugging/traceability. The normalized columns above and the
    // invoice_items table are the real, queryable data.
    @Column(name = "raw_llm_response", columnDefinition = "TEXT")
    private String rawLlmResponse;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.CREATED;

    // ---- Batch workflow (all nullable: legacy invoices have no owner/batch and stay ADMIN-only) ----

    // The vendor who uploaded the invoice.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", foreignKey = @ForeignKey(name = "fk_invoices_owner_user"))
    private AppUser owner;

    // Read-only copy of the foreign key, so access checks need no extra query.
    @Column(name = "owner_user_id", insertable = false, updatable = false)
    private Long ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", foreignKey = @ForeignKey(name = "fk_invoices_upload_batch"))
    private UploadBatch batch;

    @Column(name = "batch_id", insertable = false, updatable = false)
    private Long batchId;

    // e.g. "ELECTRONICS" (categories.code). Decides which reviewers may be assigned.
    @Column(name = "category_code", length = 50)
    private String categoryCode;

    // The reviewer (app_users) an admin assigned; only this reviewer may read/correct/submit the invoice.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_reviewer_id", foreignKey = @ForeignKey(name = "fk_invoices_assigned_reviewer"))
    private AppUser assignedReviewer;

    @Column(name = "assigned_reviewer_id", insertable = false, updatable = false)
    private Long assignedReviewerId;

    // The exact version the reviewer submitted for approval (PENDING_APPROVAL); approval must match it.
    @Column(name = "submitted_version")
    private Integer submittedVersion;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    // ---- Gemini model + token usage of the extraction call (from the response's usageMetadata) ----
    // All nullable: invoices stored before this feature have no metrics, and Gemini may omit a count.
    @Column(name = "gemini_model_id", length = 100)
    private String geminiModelId;

    @Column(name = "gemini_model_version", length = 100)
    private String geminiModelVersion;

    @Column(name = "prompt_token_count")
    private Long promptTokenCount;

    @Column(name = "candidates_token_count")
    private Long candidatesTokenCount;

    @Column(name = "thoughts_token_count")
    private Long thoughtsTokenCount;

    @Column(name = "total_token_count")
    private Long totalTokenCount;

    @Column(name = "extraction_duration_ms")
    private Long extractionDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Cascade ALL: saving the invoice also saves its line items in the same transaction.
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvoiceItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Always go through this method so both sides of the relationship stay in sync.
    public void addItem(InvoiceItem item) {
        item.setInvoice(this);
        items.add(item);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public String getSellerTaxId() { return sellerTaxId; }
    public void setSellerTaxId(String sellerTaxId) { this.sellerTaxId = sellerTaxId; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getBuyerTaxId() { return buyerTaxId; }
    public void setBuyerTaxId(String buyerTaxId) { this.buyerTaxId = buyerTaxId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getShippingAmount() { return shippingAmount; }
    public void setShippingAmount(BigDecimal shippingAmount) { this.shippingAmount = shippingAmount; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }

    public Long getSourceFileSize() { return sourceFileSize; }
    public void setSourceFileSize(Long sourceFileSize) { this.sourceFileSize = sourceFileSize; }

    public InvoiceDocument getSourceDocument() { return sourceDocument; }
    public void setSourceDocument(InvoiceDocument sourceDocument) { this.sourceDocument = sourceDocument; }

    public String getRawLlmResponse() { return rawLlmResponse; }
    public void setRawLlmResponse(String rawLlmResponse) { this.rawLlmResponse = rawLlmResponse; }

    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }

    public AppUser getOwner() { return owner; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwner(AppUser owner) {
        this.owner = owner;
        this.ownerUserId = owner == null ? null : owner.getId();
    }

    public UploadBatch getBatch() { return batch; }
    public Long getBatchId() { return batchId; }
    public void setBatch(UploadBatch batch) {
        this.batch = batch;
        this.batchId = batch == null ? null : batch.getId();
    }

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }

    public AppUser getAssignedReviewer() { return assignedReviewer; }
    public Long getAssignedReviewerId() { return assignedReviewerId; }
    public void setAssignedReviewer(AppUser reviewer) {
        this.assignedReviewer = reviewer;
        this.assignedReviewerId = reviewer == null ? null : reviewer.getId();
    }

    public Integer getSubmittedVersion() { return submittedVersion; }
    public void setSubmittedVersion(Integer submittedVersion) { this.submittedVersion = submittedVersion; }

    /** Legacy = created before the batch workflow (no owner): visible to ADMIN only, never auto-enrolled. */
    public boolean isLegacy() {
        return ownerUserId == null;
    }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getGeminiModelId() { return geminiModelId; }
    public void setGeminiModelId(String geminiModelId) { this.geminiModelId = geminiModelId; }

    public String getGeminiModelVersion() { return geminiModelVersion; }
    public void setGeminiModelVersion(String geminiModelVersion) { this.geminiModelVersion = geminiModelVersion; }

    public Long getPromptTokenCount() { return promptTokenCount; }
    public void setPromptTokenCount(Long promptTokenCount) { this.promptTokenCount = promptTokenCount; }

    public Long getCandidatesTokenCount() { return candidatesTokenCount; }
    public void setCandidatesTokenCount(Long candidatesTokenCount) { this.candidatesTokenCount = candidatesTokenCount; }

    public Long getThoughtsTokenCount() { return thoughtsTokenCount; }
    public void setThoughtsTokenCount(Long thoughtsTokenCount) { this.thoughtsTokenCount = thoughtsTokenCount; }

    public Long getTotalTokenCount() { return totalTokenCount; }
    public void setTotalTokenCount(Long totalTokenCount) { this.totalTokenCount = totalTokenCount; }

    public Long getExtractionDurationMs() { return extractionDurationMs; }
    public void setExtractionDurationMs(Long extractionDurationMs) { this.extractionDurationMs = extractionDurationMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<InvoiceItem> getItems() { return items; }
}
