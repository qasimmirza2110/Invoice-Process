package com.actify.invoice.exception;

/** Too many failed logins for this email or this client address. Maps to HTTP 429. */
public class LoginLockedException extends RuntimeException {

    public LoginLockedException() {
        super("Too many failed login attempts. Try again in 15 minutes.");
    }
}
