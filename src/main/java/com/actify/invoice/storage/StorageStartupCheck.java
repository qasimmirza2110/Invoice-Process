package com.actify.invoice.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * invoice.storage.verify-on-startup=true (set by the render profile): the application refuses to start unless the
 * PDF storage folder can really be written by this process (InvoiceDocumentStorage.verifyWritable).
 *
 * The check runs while the Spring context is created, before the web server accepts connections, so a missing or
 * read-only disk mount makes the deploy fail its health check and the previous version keeps serving - instead of
 * the first upload failing with a 500. It does not prove the folder is on a persistent disk; the post-deploy
 * redeploy test in docs/RENDER_DEPLOYMENT.md does.
 */
@Component
@ConditionalOnProperty(name = "invoice.storage.verify-on-startup", havingValue = "true")
public class StorageStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StorageStartupCheck.class);

    public StorageStartupCheck(InvoiceDocumentStorage storage) {
        storage.verifyWritable();
        log.info("Invoice PDF storage folder verified: writable by this process");
    }
}
