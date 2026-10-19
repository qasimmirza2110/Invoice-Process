package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Keeps original uploaded PDFs in a private directory (invoice.storage.dir), outside the
 * static web folder, so they are only reachable through DocumentController.
 *
 * - File names are generated UUIDs; the client's file name is never part of a path.
 * - Files are created with CREATE_NEW, so an existing file is never overwritten.
 * - The SHA-256 is checked after writing and again on every read.
 *
 * A file write and a PostgreSQL commit can't be one atomic transaction. The service deletes
 * the file when the database transaction fails; if the process dies in between, an orphan file
 * (no invoice_documents row) can remain. SQL cannot see the disk, so spotting one means comparing the
 * storage folder (files named {id}.pdf) with the invoice_documents ids by hand; no tool for that yet.
 */
@Component
public class InvoiceDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(InvoiceDocumentStorage.class);

    private static final String EXTENSION = ".pdf";

    private final Path root;

    public InvoiceDocumentStorage(@Value("${invoice.storage.dir}") String storageDir) {
        if (storageDir == null || storageDir.trim().isEmpty()) {
            throw new IllegalStateException("invoice.storage.dir must be set to an absolute directory path");
        }
        Path path = Paths.get(storageDir.trim());
        // Fail at startup rather than silently writing next to whatever the working directory is.
        if (!path.isAbsolute()) {
            throw new IllegalStateException("invoice.storage.dir must be an absolute path");
        }
        this.root = path.normalize();
    }

    /** Writes the bytes under a new UUID name and verifies them. */
    public StoredDocument store(byte[] bytes) {
        String id = UUID.randomUUID().toString();
        String sha256 = sha256Hex(bytes);
        Path target = resolve(id);
        try {
            Files.createDirectories(root);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            // Practically impossible with a random UUID - but never overwrite.
            throw new DocumentStorageException("Could not store the original PDF", e);
        } catch (IOException e) {
            deleteQuietly(id);
            throw new DocumentStorageException("Could not store the original PDF", e);
        }

        // Read back and compare, so a short/corrupted write is caught now and not at download time.
        String written;
        try {
            written = sha256Hex(Files.readAllBytes(target));
        } catch (IOException e) {
            deleteQuietly(id);
            throw new DocumentStorageException("Could not verify the stored PDF", e);
        }
        if (!sha256.equals(written)) {
            deleteQuietly(id);
            throw new DocumentStorageException("Stored PDF failed its integrity check");
        }
        return new StoredDocument(id, sha256, bytes.length);
    }

    /** Reads a stored PDF and checks it still has the SHA-256 recorded at upload time. */
    public byte[] read(String id, String expectedSha256) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolve(id));
        } catch (NoSuchFileException e) {
            log.warn("Stored file for document {} is missing", id);
            throw new DocumentNotFoundException();
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read the stored PDF", e);
        }
        if (!sha256Hex(bytes).equals(expectedSha256)) {
            log.error("Stored file for document {} does not match its recorded SHA-256", id);
            throw new DocumentStorageException("Stored PDF failed its integrity check");
        }
        return bytes;
    }

    /** Compensation after a failed database transaction. Never throws. */
    public void deleteQuietly(String id) {
        try {
            Files.deleteIfExists(resolve(id));
        } catch (IOException | RuntimeException e) {
            log.error("Could not delete orphaned stored file for document {}", id, e);
        }
    }

    public boolean exists(String id) {
        return Files.exists(resolve(id));
    }

    /**
     * Only a well-formed UUID can become a file name, and the result must stay inside the root.
     * Anything else (e.g. "../x") is treated as "not found" without touching the filesystem.
     */
    Path resolve(String id) {
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
        Path path = root.resolve(uuid + EXTENSION).normalize();
        if (!path.getParent().equals(root)) {
            throw new DocumentNotFoundException();
        }
        return path;
    }

    public static String sha256Hex(byte[] bytes) {
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
