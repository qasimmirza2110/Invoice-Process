package com.actify.invoice.exception;

/** The user change is not allowed in the current state (duplicate email, last admin...). Maps to HTTP 409. */
public class UserConflictException extends RuntimeException {

    public UserConflictException(String message) {
        super(message);
    }
}
