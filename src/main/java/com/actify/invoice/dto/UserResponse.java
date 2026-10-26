package com.actify.invoice.dto;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;

import java.time.LocalDateTime;

/** A user as shown to an ADMIN. The password hash and token version are never part of an API response. */
public class UserResponse {

    private final Long id;
    private final String email;
    private final String displayName;
    private final Role role;
    private final boolean enabled;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private UserResponse(AppUser user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
        this.createdAt = user.getCreatedAt();
        this.updatedAt = user.getUpdatedAt();
    }

    public static UserResponse from(AppUser user) {
        return new UserResponse(user);
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    /** The name the UI shows: the e-mail before the "@". */
    public String getName() { return com.actify.invoice.user.UserNames.label(displayName, email); }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
