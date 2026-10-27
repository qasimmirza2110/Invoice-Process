package com.actify.invoice.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The start is refused when the storage folder is unusable - only when the check is switched on. */
class StorageStartupCheckTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner(Path storageDir) {
        return new ApplicationContextRunner()
                .withBean(InvoiceDocumentStorage.class, () -> new InvoiceDocumentStorage(storageDir.toString()))
                .withUserConfiguration(StorageStartupCheck.class);
    }

    @Test
    void aWritableFolderStarts() {
        runner(dir.resolve("invoice-documents")).withPropertyValues("invoice.storage.verify-on-startup=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(StorageStartupCheck.class));
    }

    @Test
    void anUnwritableFolderStopsTheStart() throws Exception {
        Path notAFolder = Files.write(dir.resolve("plain-file"), new byte[]{1});
        runner(notAFolder).withPropertyValues("invoice.storage.verify-on-startup=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(java.nio.file.FileSystemException.class)
                        .hasStackTraceContaining("is not writable by this process"));
    }

    @Test
    void withoutThePropertyNothingIsCheckedAsInLocalRunsAndTests() throws Exception {
        Path notAFolder = Files.write(dir.resolve("plain-file"), new byte[]{1});
        runner(notAFolder).run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StorageStartupCheck.class));
    }
}
