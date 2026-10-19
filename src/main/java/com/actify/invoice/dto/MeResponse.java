package com.actify.invoice.dto;

import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.user.Role;

/** Who is logged in (GET /api/auth/me and the login response). Never contains the token. */
public class MeResponse {

    private final Long id;
    private final String email;
    private final String displayName;
    private final Role role;

    public MeResponse(Long id, String email, String displayName, Role role) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public static MeResponse from(AuthenticatedUser user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
}
