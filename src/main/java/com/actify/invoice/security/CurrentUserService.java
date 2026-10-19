package com.actify.invoice.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * "Who is making this request?" - read from Spring Security's SecurityContext, which
 * JwtCookieAuthenticationFilter filled from the database at the start of the request.
 */
@Component
public class CurrentUserService {

    /** The logged-in user, or null outside an authenticated request (e.g. a background job, a unit test). */
    public AuthenticatedUser currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser) {
            return (AuthenticatedUser) authentication.getPrincipal();
        }
        return null;
    }

    /** The logged-in user; fails (401) if there is none. Use in code that only runs for logged-in requests. */
    public AuthenticatedUser require() {
        AuthenticatedUser user = currentOrNull();
        if (user == null) {
            throw new AuthenticationCredentialsNotFoundException("Not logged in");
        }
        return user;
    }

    /** "user:<id>" for audit columns, or null when no user is logged in. */
    public String actorLabelOrNull() {
        AuthenticatedUser user = currentOrNull();
        return user == null ? null : user.actorLabel();
    }
}
