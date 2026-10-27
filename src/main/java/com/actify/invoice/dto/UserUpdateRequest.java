package com.actify.invoice.dto;

import com.actify.invoice.user.Role;

/**
 * Body of PUT /api/admin/users/{id}/role ({"role":"REVIEWER"}), PUT /api/admin/users/{id}/enabled ({"enabled":false})
 * and PUT /api/admin/users/{id}/display-name ({"displayName":"Sam"}). Each endpoint reads only its own field.
 */
public class UserUpdateRequest {

    private Role role;
    private Boolean enabled;
    private String displayName;

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
