package com.actify.invoice.exception;

/** The client edited an older version than the one now stored (lost-update protection). Maps to HTTP 409. */
public class VersionConflictException extends RuntimeException {

    private final int currentVersion;

    public VersionConflictException(int currentVersion, int baseVersion) {
        super("This invoice changed since you opened it (you edited version " + baseVersion
                + ", the latest is version " + currentVersion + "). Reload the latest version before saving.");
        this.currentVersion = currentVersion;
    }

    public VersionConflictException(String message, int currentVersion) {
        super(message);
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
