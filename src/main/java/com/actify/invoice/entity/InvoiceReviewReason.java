package com.actify.invoice.entity;

import com.actify.invoice.validation.ReviewReasonCode;
import org.hibernate.annotations.Immutable;

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
 * Why an extracted invoice needs attention, as found by deterministic checks on the version it belongs to
 * (V1 = the extraction). One row per problem, so dashboards can simply GROUP BY code. Append-only.
 */
@Entity
@Immutable
@Table(name = "invoice_review_reasons", indexes = {
        @Index(name = "ix_review_reasons_invoice", columnList = "invoice_id, version_number"),
        @Index(name = "ix_review_reasons_code", columnList = "code")})
public class InvoiceReviewReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_review_reasons_invoice"))
    private Invoice invoice;

    @Column(name = "version_number", nullable = false, updatable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", length = 40, nullable = false, updatable = false)
    private ReviewReasonCode code;

    @Column(name = "field", length = 100, updatable = false)
    private String field;

    @Column(name = "message", length = 1000, nullable = false, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InvoiceReviewReason() {
    }

    public InvoiceReviewReason(Invoice invoice, int versionNumber, ReviewReasonCode code, String field, String message) {
        this.invoice = invoice;
        this.versionNumber = versionNumber;
        this.code = code;
        this.field = field;
        this.message = message != null && message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Integer getVersionNumber() { return versionNumber; }
    public ReviewReasonCode getCode() { return code; }
    public String getField() { return field; }
    public String getMessage() { return message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
