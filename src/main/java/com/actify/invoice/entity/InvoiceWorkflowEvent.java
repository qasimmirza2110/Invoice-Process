package com.actify.invoice.entity;

import com.actify.invoice.user.AppUser;
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
 * Append-only audit trail: ONE row for EVERY status change of an invoice (who, when, from, to, which version, why).
 * An approval is simply the event with to_status = APPROVED and the approved version_number.
 * The repository has no update or delete method; @Immutable stops Hibernate from issuing UPDATEs.
 */
@Entity
@Immutable
@Table(name = "invoice_workflow_events", indexes = {
        @Index(name = "ix_workflow_events_invoice", columnList = "invoice_id, id"),
        @Index(name = "ix_workflow_events_to_status", columnList = "to_status, created_at")})
public class InvoiceWorkflowEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_workflow_events_invoice"))
    private Invoice invoice;

    @Column(name = "invoice_id", insertable = false, updatable = false)
    private Long invoiceId;

    // null for the first event (the invoice did not exist before).
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private InvoiceStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20, nullable = false, updatable = false)
    private InvoiceStatus toStatus;

    // The person who caused the change; null when the system did it (e.g. the extraction worker).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false, foreignKey = @ForeignKey(name = "fk_workflow_events_actor"))
    private AppUser actor;

    @Column(name = "actor_user_id", insertable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "version_number", updatable = false)
    private Integer versionNumber;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InvoiceWorkflowEvent() {
    }

    public InvoiceWorkflowEvent(Invoice invoice, InvoiceStatus fromStatus, InvoiceStatus toStatus, AppUser actor,
                                Integer versionNumber, String reason) {
        this.invoice = invoice;
        this.invoiceId = invoice.getId();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor; // actorUserId (read-only copy) is filled when the row is read back
        this.versionNumber = versionNumber;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public InvoiceStatus getFromStatus() { return fromStatus; }
    public InvoiceStatus getToStatus() { return toStatus; }
    public Long getActorUserId() { return actorUserId; }
    public Integer getVersionNumber() { return versionNumber; }
    public String getReason() { return reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
