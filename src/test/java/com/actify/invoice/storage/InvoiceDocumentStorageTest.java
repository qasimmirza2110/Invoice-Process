package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Private PDF storage on a temporary folder: exact bytes under a generated UUID name, SHA-256 recorded and re-checked
 * on read (tampering is detected), no overwrite of existing files, and no path other than a canonical UUID can be
 * resolved (path traversal protection).
 */
class InvoiceDocumentStorageTest {

    @TempDir
    Path dir;

    private final byte[] pdf = "%PDF-1.4 synthetic".getBytes(StandardCharsets.US_ASCII);

    @Test
    void refusesARelativeOrMissingStorageDirectory() {
        assertThatThrownBy(() -> new InvoiceDocumentStorage("relative/folder")).hasMessageContaining("absolute");
        assertThatThrownBy(() -> new InvoiceDocumentStorage(" ")).hasMessageContaining("absolute");
    }

    @Test
    void storesExactBytesUnderAGeneratedUuidAndRecordsTheirSha256() throws Exception {
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.resolve("docs").toString());

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
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.toString());
        StoredDocument a = storage.store(pdf);
        StoredDocument b = storage.store(pdf);
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getSha256()).isEqualTo(b.getSha256());
        assertThat(storage.exists(a.getId())).isTrue();
        assertThat(storage.exists(b.getId())).isTrue();
    }

    @Test
    void detectsATamperedFile() throws Exception {
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.toString());
        StoredDocument stored = storage.store(pdf);
        Files.write(dir.resolve(stored.getId() + ".pdf"), "%PDF-1.4 changed".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> storage.read(stored.getId(), stored.getSha256()))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessageContaining("integrity")
                .hasMessageNotContaining(dir.toString());
    }

    @Test
    void missingFileIsNotFound() {
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.toString());
        assertThatThrownBy(() -> storage.read(UUID.randomUUID().toString(), "x"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void onlyCanonicalUuidsCanBecomeFileNames() {
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.toString());
        String upper = UUID.randomUUID().toString().toUpperCase();
        for (String bad : new String[]{"../secret", "..\\secret", "a/b", "", "not-a-uuid", upper, "1-1-1-1-1", null}) {
            assertThatThrownBy(() -> storage.resolve(bad)).as(String.valueOf(bad)).isInstanceOf(DocumentNotFoundException.class);
        }
        String ok = UUID.randomUUID().toString();
        assertThat(storage.resolve(ok).getParent()).isEqualTo(dir.normalize());
    }

    @Test
    void deleteQuietlyRemovesTheFileAndNeverThrows() {
        InvoiceDocumentStorage storage = new InvoiceDocumentStorage(dir.toString());
        StoredDocument stored = storage.store(pdf);
        storage.deleteQuietly(stored.getId());
        assertThat(storage.exists(stored.getId())).isFalse();
        storage.deleteQuietly(stored.getId());       // already gone
        storage.deleteQuietly("../not-valid");        // invalid id
    }
}
