package com.actify.invoice.exception;

/**
 * Login failed. Always the same message, whether the email is unknown, the password is wrong,
 * the account is disabled or the password is too long - so a caller cannot probe which emails exist.
 * Maps to HTTP 401.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password.");
    }
}
