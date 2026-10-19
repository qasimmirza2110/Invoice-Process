package com.actify.invoice.entity;

import com.actify.invoice.user.AppUser;
import org.hibernate.annotations.ColumnTransformer;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One upload session of a vendor: files are added one per request while the batch is OPEN (at most 100 accepted).
 * Rejected files never become invoices; their name + reason are kept here for the vendor (never their content).
 */
@Entity
@Table(name = "upload_batches", indexes = @Index(name = "ix_upload_batches_owner", columnList = "owner_user_id, id"))
public class UploadBatch {

    public static final int MAX_FILES = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_upload_batches_owner_user"))
    private AppUser owner;

    // Read-only copy of the foreign key, so access checks need no extra query.
    @Column(name = "owner_user_id", insertable = false, updatable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    private BatchSource source = BatchSource.LOCAL;

    @Column(name = "label", length = 255)
    private String label;

    // Category every new invoice of this batch starts with (the admin can change it per invoice). Nullable.
    @Column(name = "default_category_code", length = 50)
    private String defaultCategoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private BatchStatus status = BatchStatus.OPEN;

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    // JSON array of {fileName, code, message}; file names are already sanitized.
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "rejections", columnDefinition = "jsonb", nullable = false)
    private String rejections = "[]";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected UploadBatch() {
    }

    public UploadBatch(AppUser owner, BatchSource source, String label, String defaultCategoryCode) {
        this.owner = owner;
        this.ownerUserId = owner.getId();
        this.source = source;
        this.label = label;
        this.defaultCategoryCode = defaultCategoryCode;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void close() {
        status = BatchStatus.CLOSED;
        closedAt = LocalDateTime.now();
    }

    public void countAccepted() { acceptedCount++; }

    public void recordRejection(String rejectionsJson) {
        rejectedCount++;
        rejections = rejectionsJson;
    }

    public Long getId() { return id; }
    public AppUser getOwner() { return owner; }
    public Long getOwnerUserId() { return ownerUserId; }
    public BatchSource getSource() { return source; }
    public String getLabel() { return label; }
    public String getDefaultCategoryCode() { return defaultCategoryCode; }
    public BatchStatus getStatus() { return status; }
    public int getAcceptedCount() { return acceptedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public String getRejections() { return rejections; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
}
