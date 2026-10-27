package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Keeps original uploaded PDFs in a private directory (invoice.storage.dir), outside the
 * static web folder, so they are only reachable through DocumentController.
 *
 * - File names are generated UUIDs; the client's file name is never part of a path.
 * - An existing file is never overwritten.
 * - The bytes are written to a temporary file (".{id}.pdf.tmp"), flushed to the device (fsync), and only then renamed
 *   atomically to "{id}.pdf". So "{id}.pdf" is either complete or absent - a crash or a full disk mid-write can never
 *   leave a truncated original under its real name. The database row is committed only after store() returns, so a
 *   committed invoice_documents row never points at bytes that were still only in the OS cache.
 * - The SHA-256 is checked after writing and again on every read.
 *
 * A file write and a PostgreSQL commit can't be one atomic transaction. The service deletes
 * the file when the database transaction fails; if the process dies in between, an orphan file
 * (no invoice_documents row) can remain. SQL cannot see the disk, so spotting one means comparing the
 * storage folder (files named {id}.pdf) with the invoice_documents ids by hand; no tool for that yet.
 * Leftover ".*.tmp" files come only from a crash during a write; they are never read and may be deleted while the
 * application is stopped.
 */
@Component
public class InvoiceDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(InvoiceDocumentStorage.class);

    private static final String EXTENSION = ".pdf";
    // A leading dot and a different suffix: resolve() can never map an id to a temporary file.
    private static final String TEMP_PREFIX = ".";
    private static final String TEMP_SUFFIX = ".tmp";

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
        Path temp = root.resolve(TEMP_PREFIX + id + EXTENSION + TEMP_SUFFIX);
        try {
            Files.createDirectories(root);
            writeDurably(temp, bytes);
            if (Files.exists(target)) {
                // Practically impossible with a random UUID - but never overwrite (an atomic rename would replace).
                throw new FileAlreadyExistsException(target.getFileName().toString());
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
        } catch (FileAlreadyExistsException e) {
            deletePathQuietly(temp);
            throw new DocumentStorageException("Could not store the original PDF", e);
        } catch (IOException e) {
            deletePathQuietly(temp);
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
     * Startup self-test (StorageStartupCheck): the directory can be created, a file written, flushed, read back
     * identically and removed - by THIS process, with ITS user. Fails with IllegalStateException otherwise, so a
     * missing or read-only mount stops the start instead of failing the first upload with a 500. It proves the
     * folder is writable now; it cannot prove the folder is on a persistent disk.
     */
    public void verifyWritable() {
        Path probe = root.resolve(TEMP_PREFIX + "write-probe-" + UUID.randomUUID() + TEMP_SUFFIX);
        byte[] content = ("invoice storage write probe " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        try {
            Files.createDirectories(root);
            writeDurably(probe, content);
            if (!Arrays.equals(Files.readAllBytes(probe), content)) {
                throw new IllegalStateException("invoice.storage.dir " + root + " returned different bytes than written");
            }
        } catch (IOException e) {
            throw new IllegalStateException("invoice.storage.dir " + root + " is not writable by this process ("
                    + e.getClass().getSimpleName() + ")", e);
        } finally {
            deletePathQuietly(probe);
        }
    }

    /** CREATE_NEW (never overwrite), every byte written, then forced to the storage device before returning. */
    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    /**
     * Makes the rename itself durable (Linux: fsync of the directory entry). Best effort: Windows cannot open a
     * directory as a channel, so there it is skipped; the file content was already forced to disk.
     */
    private void syncDirectory() {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | RuntimeException e) {
            log.debug("Directory sync not supported here ({})", e.getClass().getSimpleName());
        }
    }

    private static void deletePathQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.error("Could not delete temporary storage file {}", file.getFileName(), e);
        }
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
