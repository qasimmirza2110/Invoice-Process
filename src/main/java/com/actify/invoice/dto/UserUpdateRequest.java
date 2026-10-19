package com.actify.invoice.dto;

import com.actify.invoice.user.Role;

/**
 * Body of PUT /api/admin/users/{id}/role ({"role":"REVIEWER"}) and PUT /api/admin/users/{id}/enabled
 * ({"enabled":false}). Each endpoint reads only its own field.
 */
public class UserUpdateRequest {

    private Role role;
    private Boolean enabled;

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
