package com.actify.invoice.exception;

/** The requested workflow action is not allowed in the invoice's current status (or the batch state). Maps to HTTP 409. */
public class WorkflowConflictException extends RuntimeException {

    public WorkflowConflictException(String message) {
        super(message);
    }
}
