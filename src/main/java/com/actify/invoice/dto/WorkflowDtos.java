package com.actify.invoice.dto;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceReviewReason;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.InvoiceWorkflowEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Small request/response shapes of the review and approval workflow. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /** One row of GET /api/invoices (role-scoped list). */
    public static class InvoiceSummary {
        private final Long id;
        private final InvoiceStatus status;
        private final String fileName;
        private final String invoiceNumber;
        private final String sellerName;
        private final BigDecimal totalAmount;
        private final String currency;
        private final String categoryCode;
        private final Long ownerUserId;
        private final Long batchId;
        private final Long assignedReviewerId;
        private final Integer submittedVersion;
        private final long reviewReasonCount;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;

        public InvoiceSummary(Invoice i, long reviewReasonCount) {
            this.id = i.getId();
            this.status = i.getStatus();
            this.fileName = i.getSourceFileName();
            this.invoiceNumber = i.getInvoiceNumber();
            this.sellerName = i.getSellerName();
            this.totalAmount = i.getTotalAmount();
            this.currency = i.getCurrency();
            this.categoryCode = i.getCategoryCode();
            this.ownerUserId = i.getOwnerUserId();
            this.batchId = i.getBatchId();
            this.assignedReviewerId = i.getAssignedReviewerId();
            this.submittedVersion = i.getSubmittedVersion();
            this.reviewReasonCount = reviewReasonCount;
            this.createdAt = i.getCreatedAt();
            this.updatedAt = i.getUpdatedAt();
        }

        public Long getId() { return id; }
        public InvoiceStatus getStatus() { return status; }
        public String getFileName() { return fileName; }
        public String getInvoiceNumber() { return invoiceNumber; }
        public String getSellerName() { return sellerName; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public String getCurrency() { return currency; }
        public String getCategoryCode() { return categoryCode; }
        public Long getOwnerUserId() { return ownerUserId; }
        public Long getBatchId() { return batchId; }
        public Long getAssignedReviewerId() { return assignedReviewerId; }
        public Integer getSubmittedVersion() { return submittedVersion; }
        public long getReviewReasonCount() { return reviewReasonCount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
    }

    public static class ReviewReasonResponse {
        private final int versionNumber;
        private final String code;
        private final String field;
        private final String message;

        public ReviewReasonResponse(InvoiceReviewReason r) {
            this.versionNumber = r.getVersionNumber();
            this.code = r.getCode().name();
            this.field = r.getField();
            this.message = r.getMessage();
        }

        public int getVersionNumber() { return versionNumber; }
        public String getCode() { return code; }
        public String getField() { return field; }
        public String getMessage() { return message; }
    }

    public static class EventResponse {
        private final Long id;
        private final InvoiceStatus fromStatus;
        private final InvoiceStatus toStatus;
        private final Long actorUserId;
        private final Integer versionNumber;
        private final String reason;
        private final LocalDateTime createdAt;

        public EventResponse(InvoiceWorkflowEvent e) {
            this.id = e.getId();
            this.fromStatus = e.getFromStatus();
            this.toStatus = e.getToStatus();
            this.actorUserId = e.getActorUserId();
            this.versionNumber = e.getVersionNumber();
            this.reason = e.getReason();
            this.createdAt = e.getCreatedAt();
        }

        public Long getId() { return id; }
        public InvoiceStatus getFromStatus() { return fromStatus; }
        public InvoiceStatus getToStatus() { return toStatus; }
        /** null = done by the system (extraction worker). */
        public Long getActorUserId() { return actorUserId; }
        public Integer getVersionNumber() { return versionNumber; }
        public String getReason() { return reason; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    /** Body of submit / approve / reject / send-back / reopen / requeue. Each action reads what it needs. */
    public static class DecisionRequest {
        private Integer version;
        private String reason;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** Body of POST /api/admin/assignments. reviewerId null = remove the assignment. */
    public static class AssignmentRequest {
        private List<Long> invoiceIds;
        private Long reviewerId;

        public List<Long> getInvoiceIds() { return invoiceIds; }
        public void setInvoiceIds(List<Long> invoiceIds) { this.invoiceIds = invoiceIds; }
        public Long getReviewerId() { return reviewerId; }
        public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    }

    /** Per-invoice answer of a bulk assignment: assigned or the reason it was not. */
    public static class AssignmentResult {
        private final Long invoiceId;
        private final boolean assigned;
        private final String message;

        public AssignmentResult(Long invoiceId, boolean assigned, String message) {
            this.invoiceId = invoiceId;
            this.assigned = assigned;
            this.message = message;
        }

        public Long getInvoiceId() { return invoiceId; }
        public boolean isAssigned() { return assigned; }
        public String getMessage() { return message; }
    }
}
