package com.actify.invoice.exception;

/** Maps to HTTP 404. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found with id " + id);
    }
}
