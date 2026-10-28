package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Where the original uploaded PDFs live. Two implementations, chosen by invoice.storage.type:
 * - "local" (default): LocalDocumentStorage, a private folder on this machine - local runs and tests.
 * - "s3": S3DocumentStorage, a private S3-compatible bucket (Neon Object Storage) - required by the render profile,
 *   because a Render Free container's own disk is wiped on every deploy, restart and spin-down.
 *
 * Common rules: the document id is a server-generated UUID (the client's file name is never part of a path or key),
 * nothing is ever overwritten, and the SHA-256 recorded at upload is checked again on every read. A file is only
 * reachable through DocumentController, after the caller's role and ownership have been checked.
 *
 * Storing the bytes and committing the database row cannot be one atomic step. Callers store first, then commit, and
 * call deleteQuietly(id) when the commit fails; a crash in between can leave an orphan object with no
 * invoice_documents row (never the reverse).
 */
public interface InvoiceDocumentStorage {

    /** Stores the bytes under a new id and verifies them. Throws DocumentStorageException on any failure. */
    StoredDocument store(byte[] bytes);

    /** The stored bytes, checked against the SHA-256 recorded at upload. Missing -> DocumentNotFoundException. */
    byte[] read(String id, String expectedSha256);

    /** Compensation after a failed database transaction. Never throws. */
    void deleteQuietly(String id);

    boolean exists(String id);

    /**
     * Startup self-test (StorageStartupCheck): write, read back and remove a probe with THIS process's configuration.
     * Throws IllegalStateException with an actionable message (never a secret) when storage is unusable.
     */
    void verifyReady();

    /** True when the bytes are kept outside this machine, so they survive a new container. */
    boolean isRemote();

    /**
     * Only the canonical form of a UUID is a valid document id. Anything else (e.g. "../x") is "not found" without
     * touching the storage.
     */
    static String requireDocumentId(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DocumentNotFoundException();
        }
        // UUID.fromString accepts some non-canonical forms; only the canonical one is a valid id.
        if (!uuid.toString().equals(id)) {
            throw new DocumentNotFoundException();
        }
        return id;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
