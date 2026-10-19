package com.actify.invoice.dto;

import com.actify.invoice.user.Role;

/** Body of POST /api/admin/users. Never logged: it contains the initial password. */
public class CreateUserRequest {

    private String email;
    private String displayName;
    private Role role;
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @Override
    public String toString() {
        return "CreateUserRequest{role=" + role + ", password hidden}";
    }
}
