package com.actify.invoice.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The start is refused when PDF storage is unusable or (render) not remote - only when the check is switched on. */
class StorageStartupCheckTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner(Path storageDir) {
        return new ApplicationContextRunner()
                .withBean(InvoiceDocumentStorage.class, () -> new LocalDocumentStorage(storageDir.toString()))
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

    // ---- the render profile's durability gate: local storage is refused outright, there is no "accept" switch

    @Test
    void localStorageRefusesTheStartWhenRemoteStorageIsRequiredEvenIfTheFolderWorks() {
        runner(dir.resolve("invoice-documents")).withPropertyValues("invoice.storage.verify-on-startup=true",
                        "invoice.storage.require-remote=true",
                        // the removed escape hatch must have no effect any more
                        "invoice.storage.ephemeral-accepted=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("REFUSING to start")
                        .hasStackTraceContaining("needs remote document storage"));
    }

    @Test
    void aRemoteStorageThatPassesItsProbeStarts() {
        InvoiceDocumentStorage remote = mock(InvoiceDocumentStorage.class);
        when(remote.isRemote()).thenReturn(true);
        assertThatCode(() -> new StorageStartupCheck(remote, true)).doesNotThrowAnyException();
        verify(remote).verifyReady();
    }

    @Test
    void aRemoteStorageThatFailsItsProbeStopsTheStart() {
        InvoiceDocumentStorage remote = mock(InvoiceDocumentStorage.class);
        when(remote.isRemote()).thenReturn(true);
        doThrow(new IllegalStateException("Document storage is not usable: HTTP 403 AccessDenied"))
                .when(remote).verifyReady();
        assertThatThrownBy(() -> new StorageStartupCheck(remote, true)).hasMessageContaining("not usable");
    }

    @Test
    void theRemoteRequirementIsCheckedBeforeAnyProbe() {
        InvoiceDocumentStorage local = mock(InvoiceDocumentStorage.class);
        assertThatThrownBy(() -> new StorageStartupCheck(local, true)).hasMessageContaining("REFUSING");
        verify(local, never()).verifyReady();
    }
}
