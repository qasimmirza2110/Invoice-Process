package com.actify.invoice.config;

/** Test-only access to FreeDemoUploadPolicy's package-private constructor (no Spring context needed). */
public final class FreeDemoUploadPolicyAccess {

    private FreeDemoUploadPolicyAccess() {
    }

    public static FreeDemoUploadPolicy policy(boolean enforced, String allowlist) {
        return new FreeDemoUploadPolicy(enforced, allowlist);
    }
}
