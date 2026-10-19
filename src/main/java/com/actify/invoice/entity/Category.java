package com.actify.invoice.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * An invoice category such as ELECTRONICS or FURNITURE. Categories are DATA managed by an admin, not code:
 * which reviewer handles which category is stored in reviewer_categories, never written into Java conditions.
 */
@Entity
@Table(name = "categories")
public class Category {

    // Upper-case code, e.g. "ELECTRONICS". Also the value stored in invoices.category_code.
    @Id
    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    // Inactive categories stay on old invoices but cannot be chosen for new ones.
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Category() {
    }

    public Category(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setActive(boolean active) { this.active = active; }
}
