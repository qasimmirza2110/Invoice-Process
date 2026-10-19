package com.actify.invoice.entity;

/**
 * Where the files of a batch came from. Only LOCAL exists today; the cloud values are reserved for the
 * Google Drive / OneDrive phases, whose files will go through exactly the same intake checks.
 */
public enum BatchSource {
    LOCAL,
    GOOGLE_DRIVE,
    ONEDRIVE
}
