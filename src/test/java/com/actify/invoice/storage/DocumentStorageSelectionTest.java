package com.actify.invoice.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** invoice.storage.type picks exactly one storage bean; the render combination (s3 + checks) uses the bucket. */
class DocumentStorageSelectionTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LocalDocumentStorage.class, S3DocumentStorage.class, StorageStartupCheck.class)
                .withPropertyValues("invoice.storage.dir=" + dir.resolve("docs"));
    }

    @Test
    void withoutATypeTheLocalFolderIsUsed() {
        runner().run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(InvoiceDocumentStorage.class).hasSingleBean(LocalDocumentStorage.class));
    }

    @Test
    void typeS3UsesTheBucketAndPassesTheRenderChecks() throws Exception {
        try (FakeS3Server server = new FakeS3Server()) {
            runner().withPropertyValues("invoice.storage.type=s3",
                            "invoice.storage.verify-on-startup=true", "invoice.storage.require-remote=true",
                            "invoice.storage.s3.endpoint=" + server.endpoint(), "invoice.storage.s3.region=us-east-2",
                            "invoice.storage.s3.bucket=invoice-documents", "invoice.storage.s3.access-key-id=k",
                            "invoice.storage.s3.secret-access-key=s", "invoice.storage.s3.key-prefix=invoice-documents/")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(InvoiceDocumentStorage.class)
                                .hasSingleBean(S3DocumentStorage.class).doesNotHaveBean(LocalDocumentStorage.class);
                        assertThat(context).hasSingleBean(StorageStartupCheck.class);
                    });
        }
    }

    @Test
    void typeS3WithoutItsSettingsStopsTheStartInsteadOfFallingBackToDisk() {
        runner().withPropertyValues("invoice.storage.type=s3")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("INVOICE_S3_ENDPOINT"));
    }
}
