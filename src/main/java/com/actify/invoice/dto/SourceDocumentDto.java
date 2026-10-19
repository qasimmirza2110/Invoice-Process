package com.actify.invoice.dto;

import com.actify.invoice.entity.InvoiceDocument;

import java.time.LocalDateTime;

/** The stored original PDF of an invoice. url is the only way to fetch it (GET, served by DocumentController). */
public class SourceDocumentDto {

    private final String id;
    private final String sha256;
    private final Long sizeBytes;
    private final String contentType;
    private final String originalFileName;
    private final LocalDateTime createdAt;
    private final String url;

    private SourceDocumentDto(InvoiceDocument document) {
        this.id = document.getId();
        this.sha256 = document.getSha256();
        this.sizeBytes = document.getSizeBytes();
        this.contentType = document.getContentType();
        this.originalFileName = document.getOriginalFileName();
        this.createdAt = document.getCreatedAt();
        this.url = "/api/documents/" + document.getId();
    }

    public static SourceDocumentDto fromEntity(InvoiceDocument document) {
        return document == null ? null : new SourceDocumentDto(document);
    }

    public String getId() { return id; }
    public String getSha256() { return sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public String getOriginalFileName() { return originalFileName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getUrl() { return url; }
}
