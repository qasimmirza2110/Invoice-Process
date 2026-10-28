package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Private PDF storage on a local folder (invoice.storage.type=local: local runs and tests). Exact bytes under a
 * generated UUID name, SHA-256 recorded and re-checked on read (tampering is detected), no overwrite of existing files,
 * no temporary file left behind, and no path other than a canonical UUID can be resolved (path traversal protection).
 * Render never uses this class: it stores PDFs in the private bucket (S3DocumentStorageTest).
 */
class LocalDocumentStorageTest {

    @TempDir
    Path dir;

    private final byte[] pdf = "%PDF-1.4 synthetic".getBytes(StandardCharsets.US_ASCII);

    @Test
    void refusesARelativeOrMissingStorageDirectory() {
        assertThatThrownBy(() -> new LocalDocumentStorage("relative/folder")).hasMessageContaining("absolute");
        assertThatThrownBy(() -> new LocalDocumentStorage(" ")).hasMessageContaining("absolute");
    }

    @Test
    void storesExactBytesUnderAGeneratedUuidAndRecordsTheirSha256() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.resolve("docs").toString());

        StoredDocument stored = storage.store(pdf);

        assertThat(UUID.fromString(stored.getId()).toString()).isEqualTo(stored.getId());
        assertThat(stored.getSha256()).isEqualTo(InvoiceDocumentStorage.sha256Hex(pdf)).hasSize(64);
        assertThat(stored.getSizeBytes()).isEqualTo(pdf.length);
        assertThat(Files.readAllBytes(dir.resolve("docs").resolve(stored.getId() + ".pdf"))).isEqualTo(pdf);
        assertThat(storage.read(stored.getId(), stored.getSha256())).isEqualTo(pdf);
    }

    @Test
    void knownSha256Value() {
        // sha256("abc") from FIPS 180-2.
        assertThat(InvoiceDocumentStorage.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void identicalUploadsGetSeparateFilesAndNeverOverwriteEachOther() {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        StoredDocument a = storage.store(pdf);
        StoredDocument b = storage.store(pdf);
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getSha256()).isEqualTo(b.getSha256());
        assertThat(storage.exists(a.getId())).isTrue();
        assertThat(storage.exists(b.getId())).isTrue();
    }

    @Test
    void detectsATamperedFile() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        StoredDocument stored = storage.store(pdf);
        Files.write(dir.resolve(stored.getId() + ".pdf"), "%PDF-1.4 changed".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> storage.read(stored.getId(), stored.getSha256()))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessageContaining("integrity")
                .hasMessageNotContaining(dir.toString());
    }

    @Test
    void missingFileIsNotFound() {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        assertThatThrownBy(() -> storage.read(UUID.randomUUID().toString(), "x"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void onlyCanonicalUuidsCanBecomeFileNames() {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        String upper = UUID.randomUUID().toString().toUpperCase();
        for (String bad : new String[]{"../secret", "..\\secret", "a/b", "", "not-a-uuid", upper, "1-1-1-1-1", null}) {
            assertThatThrownBy(() -> storage.resolve(bad)).as(String.valueOf(bad)).isInstanceOf(DocumentNotFoundException.class);
        }
        String ok = UUID.randomUUID().toString();
        assertThat(storage.resolve(ok).getParent()).isEqualTo(dir.normalize());
    }

    @Test
    void aStoredDocumentLeavesOnlyItsFinalFileNoTemporaryFile() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        StoredDocument a = storage.store(pdf);
        StoredDocument b = storage.store(pdf);

        assertThat(fileNames(dir)).containsExactlyInAnyOrder(a.getId() + ".pdf", b.getId() + ".pdf");
    }

    @Test
    void anUnusableStorageFolderFailsCleanlyWithoutAPathInTheMessageAndLeavesNothingBehind() throws Exception {
        Path notAFolder = Files.write(dir.resolve("plain-file"), new byte[]{1});
        LocalDocumentStorage storage = new LocalDocumentStorage(notAFolder.toString());

        assertThatThrownBy(() -> storage.store(pdf))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessage("Could not store the original PDF")
                .hasMessageNotContaining(dir.toString());
        assertThat(fileNames(dir)).containsExactly("plain-file");
        assertThat(Files.readAllBytes(notAFolder)).containsExactly(1);
    }

    @Test
    void verifyReadyCreatesTheFolderProbesItAndLeavesItEmpty() throws Exception {
        Path folder = dir.resolve("mount").resolve("invoice-documents");
        new LocalDocumentStorage(folder.toString()).verifyReady();

        assertThat(Files.isDirectory(folder)).isTrue();
        assertThat(fileNames(folder)).isEmpty();
    }

    @Test
    void verifyReadyRefusesAFolderThatCannotBeWritten() throws Exception {
        Path notAFolder = Files.write(dir.resolve("plain-file"), new byte[]{1});

        assertThatThrownBy(() -> new LocalDocumentStorage(notAFolder.toString()).verifyReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not writable by this process");
        assertThat(fileNames(dir)).containsExactly("plain-file");
    }

    private static List<String> fileNames(Path folder) throws Exception {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        }
    }

    @Test
    void deleteQuietlyRemovesTheFileAndNeverThrows() {
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        StoredDocument stored = storage.store(pdf);
        storage.deleteQuietly(stored.getId());
        assertThat(storage.exists(stored.getId())).isFalse();
        storage.deleteQuietly(stored.getId());       // already gone
        storage.deleteQuietly("../not-valid");        // invalid id
    }
}
