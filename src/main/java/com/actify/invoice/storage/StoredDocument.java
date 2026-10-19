package com.actify.invoice.storage;

/** A PDF that has been written to private storage: its generated id, SHA-256 and size. */
public class StoredDocument {

    private final String id;
    private final String sha256;
    private final long sizeBytes;

    public StoredDocument(String id, String sha256, long sizeBytes) {
        this.id = id;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
    }

    public String getId() { return id; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
}
