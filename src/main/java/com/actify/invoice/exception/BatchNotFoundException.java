package com.actify.invoice.exception;

/** Unknown batch, or a batch of another vendor (same answer, so ids cannot be probed). Maps to HTTP 404. */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(Long id) {
        super("Batch not found with id " + id);
    }
}
