package com.actify.invoice.exception;

/**
 * The stored PDF could not be written/read, or failed its SHA-256 integrity check. Maps to HTTP 500.
 * The message never contains a filesystem path, so it is safe to show to clients.
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message) {
        super(message);
    }

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
