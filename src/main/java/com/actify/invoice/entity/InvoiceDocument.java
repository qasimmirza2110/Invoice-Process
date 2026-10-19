package com.actify.invoice.entity;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.time.LocalDateTime;

/**
 * Metadata of one original uploaded PDF. The bytes live in private file storage
 * (InvoiceDocumentStorage), never in the static web folder. The row is immutable:
 * the original PDF is never edited, corrections only create new invoice versions.
 *
 * The id is an application-generated UUID and is also the storage file name,
 * so a client-supplied file name is never used as a path.
 */
@Entity
@Immutable
@Table(name = "invoice_documents")
public class InvoiceDocument implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    // SHA-256 of the exact uploaded bytes, as 64 lowercase hex characters.
    @Column(name = "sha256", length = 64, nullable = false, updatable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private Long sizeBytes;

    @Column(name = "content_type", length = 100, nullable = false, updatable = false)
    private String contentType;

    // Display metadata only - never used to build a file path.
    @Column(name = "original_file_name", length = 255, updatable = false)
    private String originalFileName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // The id is assigned by us, so Spring Data can't tell new from existing by "id == null".
    @Transient
    private boolean isNew = true;

    protected InvoiceDocument() {
    }

    public InvoiceDocument(String id, String sha256, Long sizeBytes, String contentType, String originalFileName) {
        this.id = id;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.originalFileName = originalFileName;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    @Override
    public String getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    public String getSha256() { return sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public String getOriginalFileName() { return originalFileName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
