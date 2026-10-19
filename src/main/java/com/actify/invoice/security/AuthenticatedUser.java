package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;

/**
 * The logged-in user for the current request, as loaded FROM THE DATABASE by JwtCookieAuthenticationFilter.
 * Controllers and services read it through CurrentUserService. It never comes from browser input.
 */
public final class AuthenticatedUser {

    private final Long id;
    private final String email;
    private final String displayName;
    private final Role role;

    public AuthenticatedUser(Long id, String email, String displayName, Role role) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public static AuthenticatedUser from(AppUser user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** The stable identity written to audit columns such as invoice_versions.changed_by. */
    public String actorLabel() {
        return "user:" + id;
    }

    @Override
    public String toString() {
        // Used by Spring Security's getName(); no email, so it is safe in logs.
        return actorLabel();
    }
}
