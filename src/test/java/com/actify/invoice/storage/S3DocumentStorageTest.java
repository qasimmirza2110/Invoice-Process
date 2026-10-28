package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import com.actify.invoice.service.TestPdfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The private-bucket storage used on Render, driven through the REAL AWS SDK client against FakeS3Server on 127.0.0.1.
 * Proves: path-style SigV4 requests, opaque UUID keys, no optional checksum/chunked encoding, the credentials never in
 * a URL, integrity checks on write and read, the cleanup when a write fails, that a fresh client (= a restarted or
 * redeployed app) reads the same PDF, and that a public bucket or missing settings stop the start.
 * It does not prove a Neon account works - that is the live smoke test in docs/RENDER_DEPLOYMENT.md.
 */
class S3DocumentStorageTest {

    private static final String BUCKET = "invoice-documents-test";
    private static final String ACCESS_KEY = "test-access-key-id";
    private static final String SECRET = "test-secret-access-key-value";

    private FakeS3Server server;
    private final List<S3DocumentStorage> opened = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new FakeS3Server();
    }

    @AfterEach
    void stop() {
        opened.forEach(S3DocumentStorage::destroy);
        server.close();
    }

    private S3DocumentStorage storage() {
        S3DocumentStorage storage = new S3DocumentStorage(server.endpoint(), "us-east-2", BUCKET, ACCESS_KEY, SECRET,
                "invoice-documents/");
        opened.add(storage);
        return storage;
    }

    @Test
    void aPdfIsStoredUnderAnOpaqueKeyAndReadBackExactly() {
        byte[] pdf = TestPdfs.onePage("S3-0001");
        S3DocumentStorage storage = storage();

        StoredDocument stored = storage.store(pdf);

        assertThat(stored.getSha256()).isEqualTo(InvoiceDocumentStorage.sha256Hex(pdf));
        assertThat(stored.getSizeBytes()).isEqualTo(pdf.length);
        String path = "/" + BUCKET + "/invoice-documents/" + stored.getId() + ".pdf";
        assertThat(server.objects).containsOnlyKeys(path);
        assertThat(server.objects.get(path).metadata).containsEntry("sha256", stored.getSha256());
        assertThat(storage.read(stored.getId(), stored.getSha256())).isEqualTo(pdf);
        assertThat(storage.exists(stored.getId())).isTrue();
        assertThat(storage.isRemote()).isTrue();
    }

    @Test
    void requestsArePathStyleSigV4WithoutChunkedEncodingAndNeverCarryTheSecret() {
        S3DocumentStorage storage = storage();
        StoredDocument stored = storage.store(TestPdfs.onePage("S3-0002"));
        storage.read(stored.getId(), stored.getSha256());

        assertThat(server.requests).isNotEmpty().allSatisfy(r -> {
            assertThat(r.path).startsWith("/" + BUCKET + "/");
            assertThat(r.headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/")
                    .contains("/us-east-2/s3/aws4_request");
            assertThat(r.headers.getOrDefault("Content-Encoding", "")).doesNotContain("aws-chunked");
            assertThat(r.headers.getOrDefault("x-amz-content-sha256", "")).doesNotStartWith("STREAMING-");
            assertThat(r.headers).doesNotContainKey("x-amz-trailer").doesNotContainKey("x-amz-decoded-content-length");
            assertThat(r.path).doesNotContain(SECRET);
            assertThat(r.headers.values()).noneMatch(v -> v.contains(SECRET));
        });
        assertThat(server.requests).anyMatch(r -> "PUT".equals(r.method)
                && "application/pdf".equals(r.headers.get("Content-Type")));
    }

    @Test
    void aRestartedApplicationReadsTheSamePdfFromTheBucketNotFromLocalDisk() {
        byte[] pdf = TestPdfs.onePage("S3-0003");
        S3DocumentStorage before = storage();
        StoredDocument stored = before.store(pdf);
        before.destroy();
        opened.remove(before);

        // A new client with nothing in memory - what a Render restart, redeploy or spin-down/wake produces.
        assertThat(storage().read(stored.getId(), stored.getSha256())).isEqualTo(pdf);
    }

    @Test
    void aFailedWriteLeavesNoObjectAndReportsAStorageError() {
        server.failPuts.set(true);
        S3DocumentStorage storage = storage();

        assertThatThrownBy(() -> storage.store(TestPdfs.onePage("S3-0004")))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessage("Could not store the original PDF");
        assertThat(server.objects).isEmpty();
    }

    @Test
    void aStoreThatDropsTheChecksumMetadataIsRejectedAndCleanedUp() {
        server.dropMetadata.set(true);
        S3DocumentStorage storage = storage();

        assertThatThrownBy(() -> storage.store(TestPdfs.onePage("S3-0005")))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessage("Stored PDF failed its integrity check");
        assertThat(server.objects).isEmpty();
    }

    @Test
    void tamperedBytesAreDetectedOnRead() {
        S3DocumentStorage storage = storage();
        StoredDocument stored = storage.store(TestPdfs.onePage("S3-0006"));
        String path = "/" + BUCKET + "/invoice-documents/" + stored.getId() + ".pdf";
        server.objects.put(path, new FakeS3Server.StoredObject(TestPdfs.onePage("other"), server.objects.get(path).metadata));

        assertThatThrownBy(() -> storage.read(stored.getId(), stored.getSha256()))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessage("Stored PDF failed its integrity check");
    }

    @Test
    void aMissingObjectIsNotFoundAndAnInvalidIdNeverReachesTheBucket() {
        S3DocumentStorage storage = storage();
        assertThatThrownBy(() -> storage.read("6f1c1d1e-0000-4000-8000-000000000000", "x"))
                .isInstanceOf(DocumentNotFoundException.class);
        int before = server.requests.size();
        assertThatThrownBy(() -> storage.read("../../other-bucket/secret", "x")).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> storage.read("6F1C1D1E-0000-4000-8000-000000000000", "x"))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThat(server.requests).hasSize(before);
    }

    @Test
    void deleteQuietlyRemovesTheObjectAndNeverThrows() {
        S3DocumentStorage storage = storage();
        StoredDocument stored = storage.store(TestPdfs.onePage("S3-0007"));
        storage.deleteQuietly(stored.getId());
        assertThat(server.objects).isEmpty();
        storage.deleteQuietly("not-a-uuid");
        server.close();
        storage.deleteQuietly(stored.getId()); // store unreachable: logged, not thrown
    }

    // ---- startup readiness check

    @Test
    void verifyReadyPassesOnAPrivateBucketAndLeavesNoProbeBehind() {
        storage().verifyReady();
        assertThat(server.objects).isEmpty();
        // The anonymous check really was made, and was refused.
        assertThat(server.requests).anyMatch(r -> "GET".equals(r.method) && !r.headers.containsKey("Authorization"));
    }

    @Test
    void aPubliclyReadableBucketStopsTheStart() {
        server.publicRead.set(true);
        assertThatThrownBy(() -> storage().verifyReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can be read WITHOUT credentials");
        assertThat(server.objects).isEmpty();
    }

    @Test
    void anUnusableStoreStopsTheStartWithAnActionableMessageAndNoSecret() {
        server.failPuts.set(true);
        assertThatThrownBy(() -> storage().verifyReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500 InternalError")
                .hasMessageContaining("INVOICE_S3_BUCKET")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY));
    }

    @Test
    void aStoreThatDropsMetadataStopsTheStart() {
        server.dropMetadata.set(true);
        assertThatThrownBy(() -> storage().verifyReady()).hasMessageContaining("sha256 metadata");
    }

    // ---- configuration

    @Test
    void missingSettingsNameTheEnvironmentVariablesButNeverAValue() {
        assertThatThrownBy(() -> new S3DocumentStorage("", "", "", ACCESS_KEY, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVOICE_S3_ENDPOINT, INVOICE_S3_REGION, INVOICE_S3_BUCKET, INVOICE_S3_SECRET_ACCESS_KEY")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(ACCESS_KEY));
    }

    @Test
    void onlyHttpsEndpointsWithJustAHostAreAccepted() {
        assertThat(S3DocumentStorage.parseEndpoint("https://br-x.storage.c-1.us-east-2.aws.neon.tech/").toString())
                .isEqualTo("https://br-x.storage.c-1.us-east-2.aws.neon.tech");
        for (String bad : new String[]{"http://storage.example.com", "https://user:pw@storage.example.com",
                "https://storage.example.com/bucket", "https://storage.example.com?x=1", "storage.example.com", ""}) {
            assertThatThrownBy(() -> S3DocumentStorage.parseEndpoint(bad)).as(bad)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("https://");
        }
    }

    @Test
    void invalidBucketNamesAndKeyPrefixesAreRefused() {
        assertThatThrownBy(() -> new S3DocumentStorage(server.endpoint(), "us-east-2", "Bad_Bucket", ACCESS_KEY, SECRET, ""))
                .hasMessageContaining("INVOICE_S3_BUCKET");
        assertThatThrownBy(() -> new S3DocumentStorage(server.endpoint(), "us-east-2", BUCKET, ACCESS_KEY, SECRET, "../x/"))
                .hasMessageContaining("key-prefix");
    }
}
