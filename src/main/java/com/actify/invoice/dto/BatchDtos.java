package com.actify.invoice.dto;

import com.actify.invoice.entity.BatchSource;
import com.actify.invoice.entity.BatchStatus;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.UploadBatch;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Request/response shapes of the batch API (/api/batches). Grouped in one file because they are small
 * and only used together; each one is a plain class with getters, like the other DTOs.
 */
public final class BatchDtos {

    private BatchDtos() {
    }

    /** Body of POST /api/batches. */
    public static class CreateBatchRequest {
        private String label;
        private String defaultCategoryCode;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getDefaultCategoryCode() { return defaultCategoryCode; }
        public void setDefaultCategoryCode(String defaultCategoryCode) { this.defaultCategoryCode = defaultCategoryCode; }
    }

    /** One rejected file: its (sanitized) name and why. Never its content. */
    public static class RejectedFile {
        private String fileName;
        private String code;
        private String message;

        public RejectedFile() {
        }

        public RejectedFile(String fileName, String code, String message) {
            this.fileName = fileName;
            this.code = code;
            this.message = message;
        }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** Answer of POST /api/batches/{id}/files: 202 when accepted (queued), 422 when rejected. */
    public static class FileIntakeResponse {
        private final String fileName;
        private final boolean accepted;
        private final Long invoiceId;
        private final InvoiceStatus status;
        private final String code;
        private final String message;

        private FileIntakeResponse(String fileName, boolean accepted, Long invoiceId, InvoiceStatus status,
                                   String code, String message) {
            this.fileName = fileName;
            this.accepted = accepted;
            this.invoiceId = invoiceId;
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public static FileIntakeResponse accepted(String fileName, Long invoiceId) {
            return new FileIntakeResponse(fileName, true, invoiceId, InvoiceStatus.QUEUED, null,
                    "Accepted and queued for extraction.");
        }

        public static FileIntakeResponse rejected(String fileName, String code, String message) {
            return new FileIntakeResponse(fileName, false, null, null, code, message);
        }

        public String getFileName() { return fileName; }
        public boolean isAccepted() { return accepted; }
        public Long getInvoiceId() { return invoiceId; }
        public InvoiceStatus getStatus() { return status; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
    }

    /** A batch in a list. */
    public static class BatchSummary {
        private final Long id;
        private final String label;
        private final BatchSource source;
        private final BatchStatus status;
        private final String defaultCategoryCode;
        private final Long ownerUserId;
        private final int acceptedCount;
        private final int rejectedCount;
        private final LocalDateTime createdAt;
        private final LocalDateTime closedAt;

        public BatchSummary(UploadBatch b) {
            this.id = b.getId();
            this.label = b.getLabel();
            this.source = b.getSource();
            this.status = b.getStatus();
            this.defaultCategoryCode = b.getDefaultCategoryCode();
            this.ownerUserId = b.getOwnerUserId();
            this.acceptedCount = b.getAcceptedCount();
            this.rejectedCount = b.getRejectedCount();
            this.createdAt = b.getCreatedAt();
            this.closedAt = b.getClosedAt();
        }

        public Long getId() { return id; }
        public String getLabel() { return label; }
        public BatchSource getSource() { return source; }
        public BatchStatus getStatus() { return status; }
        public String getDefaultCategoryCode() { return defaultCategoryCode; }
        public Long getOwnerUserId() { return ownerUserId; }
        public int getAcceptedCount() { return acceptedCount; }
        public int getRejectedCount() { return rejectedCount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getClosedAt() { return closedAt; }
    }

    /** One invoice of a batch, as the vendor sees it while it moves through the workflow. */
    public static class BatchInvoice {
        private final Long id;
        private final String fileName;
        private final InvoiceStatus status;
        private final Long queuePosition;
        private final String failureReason;
        private final LocalDateTime createdAt;

        public BatchInvoice(Long id, String fileName, InvoiceStatus status, Long queuePosition, String failureReason,
                            LocalDateTime createdAt) {
            this.id = id;
            this.fileName = fileName;
            this.status = status;
            this.queuePosition = queuePosition;
            this.failureReason = failureReason;
            this.createdAt = createdAt;
        }

        public Long getId() { return id; }
        public String getFileName() { return fileName; }
        public InvoiceStatus getStatus() { return status; }
        /** 1 = next to be extracted; only for QUEUED invoices. */
        public Long getQueuePosition() { return queuePosition; }
        public String getFailureReason() { return failureReason; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    /** GET /api/batches/{id}: the batch, every invoice in it, counts per status and the rejected files. */
    public static class BatchDetail {
        private final BatchSummary batch;
        private final Map<InvoiceStatus, Long> statusCounts;
        private final List<BatchInvoice> invoices;
        private final List<RejectedFile> rejections;

        public BatchDetail(BatchSummary batch, Map<InvoiceStatus, Long> statusCounts, List<BatchInvoice> invoices,
                           List<RejectedFile> rejections) {
            this.batch = batch;
            this.statusCounts = statusCounts;
            this.invoices = invoices;
            this.rejections = rejections;
        }

        public BatchSummary getBatch() { return batch; }
        public Map<InvoiceStatus, Long> getStatusCounts() { return statusCounts; }
        public List<BatchInvoice> getInvoices() { return invoices; }
        public List<RejectedFile> getRejections() { return rejections; }
    }
}
