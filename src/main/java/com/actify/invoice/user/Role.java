package com.actify.invoice.user;

/**
 * The three roles of the application. Stored as text in app_users.role.
 * Spring Security sees them as authorities "ROLE_ADMIN", "ROLE_VENDOR", "ROLE_REVIEWER".
 */
public enum Role {
    /** Manages users, sees every invoice, does the final approval. */
    ADMIN,
    /** Uploads invoices and sees only their own uploads (owned uploads arrive in Phase 2). */
    VENDOR,
    /** Reviews and corrects invoices assigned to them (review workspace arrives in Phase 4). */
    REVIEWER
}
