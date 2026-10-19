package com.actify.invoice.entity;

import com.actify.invoice.user.AppUser;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * "Reviewer X handles category Y" - the routing rule is DATA in this table, set by an admin.
 * An invoice may only be assigned to a reviewer who has the invoice's category here.
 */
@Entity
@Table(name = "reviewer_categories",
        uniqueConstraints = @UniqueConstraint(name = "uk_reviewer_categories", columnNames = {"user_id", "category_code"}))
public class ReviewerCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reviewer_categories_user"))
    private AppUser reviewer;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_code", nullable = false, foreignKey = @ForeignKey(name = "fk_reviewer_categories_category"))
    private Category category;

    @Column(name = "category_code", insertable = false, updatable = false)
    private String categoryCode;

    protected ReviewerCategory() {
    }

    public ReviewerCategory(AppUser reviewer, Category category) {
        this.reviewer = reviewer;
        this.category = category;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCategoryCode() { return categoryCode; }
}
