package com.actify.invoice.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * invoice.storage.verify-on-startup=true (set by the render profile): the application refuses to start unless PDF
 * storage really works for this process (InvoiceDocumentStorage.verifyReady - for the S3 bucket also: it is private).
 *
 * invoice.storage.require-remote=true (also render): storage must be OUTSIDE the container. A Render Free container's
 * own disk is wiped on every deploy, restart and spin-down, so local storage there would silently lose every
 * uploaded invoice. There is deliberately no switch to accept that.
 *
 * The check runs while the Spring context is created, before the web server accepts connections, so a missing or
 * wrong storage setting makes the deploy fail its health check and the previous version keeps serving.
 */
@Component
@ConditionalOnProperty(name = "invoice.storage.verify-on-startup", havingValue = "true")
public class StorageStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StorageStartupCheck.class);

    @Autowired
    public StorageStartupCheck(InvoiceDocumentStorage storage,
                               @Value("${invoice.storage.require-remote:false}") boolean requireRemote) {
        if (requireRemote && !storage.isRemote()) {
            throw new IllegalStateException("REFUSING to start: this profile needs remote document storage, but "
                    + "invoice.storage.type is not \"s3\". Uploaded PDFs would be lost on the next deploy or restart. "
                    + "Configure the private bucket (INVOICE_S3_* variables, docs/RENDER_DEPLOYMENT.md) and remove any "
                    + "INVOICE_STORAGE_TYPE override.");
        }
        storage.verifyReady();
        log.info("Invoice PDF storage verified ({})", storage.isRemote() ? "remote bucket" : "local folder");
    }

    public StorageStartupCheck(InvoiceDocumentStorage storage) {
        this(storage, false);
    }
}
