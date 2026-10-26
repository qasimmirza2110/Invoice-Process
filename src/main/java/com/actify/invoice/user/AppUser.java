package com.actify.invoice.user;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * A person who can log in. The password is only ever stored as a BCrypt hash.
 *
 * tokenVersion is copied into every login token ("tv" claim). Raising it (disable, role change,
 * "log out everywhere") makes every token issued before that moment invalid on its next request.
 */
@Entity
@Table(name = "app_users",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"))
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Always stored lower-case and trimmed (AppUserService.normalizeEmail), so the unique
    // constraint also means "unique ignoring case".
    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    // BCrypt output is 60 characters; 100 leaves room for a future encoder prefix.
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppUser() {
    }

    public AppUser(String email, String displayName, String passwordHash, Role role) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Invalidates every token issued so far for this user. */
    public void bumpTokenVersion() {
        tokenVersion++;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    /** Shown in the UI: the e-mail before the "@" (see UserNames). */
    public String getName() { return UserNames.label(displayName, email); }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public int getTokenVersion() { return tokenVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setRole(Role role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // Used by tests that build a user without a database.
    public void setIdForTest(Long id) { this.id = id; }
}
