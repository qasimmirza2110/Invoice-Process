package com.actify.invoice.exception;

/** Unknown or malformed document id, or the stored file is gone. Maps to HTTP 404. */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException() {
        super("Document not found");
    }
}
