package com.actify.invoice.storage;

import com.actify.invoice.exception.DocumentNotFoundException;
import com.actify.invoice.exception.DocumentStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * invoice.storage.type=s3 (set by the render profile): original PDFs in a PRIVATE S3-compatible bucket - Neon Object
 * Storage on the free stack - so they survive Render Free restarts, deploys and spin-downs.
 *
 * - Object key: "{key-prefix}{uuid}.pdf". The uuid is generated here and is also the invoice_documents id, so no extra
 *   column is needed and the client's file name never reaches the key.
 * - Never overwrite: a HEAD before each PUT (a random UUID makes a clash practically impossible anyway).
 * - Integrity: the SHA-256 travels as object metadata, is compared with a HEAD right after the PUT, and the bytes are
 *   re-hashed against the database value on every read.
 * - The bucket must be private. verifyReady() refuses to start when an unauthenticated GET can read an object.
 *   No URL to the bucket is ever returned to a client; downloads go through DocumentController after the access
 *   checks. The credentials live only in environment variables and are never logged.
 * - Neon specifics (docs checked 2026-10-28): SigV4 only, path-style addressing only. Optional request checksums and
 *   "aws-chunked" streaming uploads are switched off, so a PUT is a plain body with Content-Length - the same wire
 *   format as boto3 / the AWS CLI, which Neon documents as supported. The SHA-256 above is our own end-to-end check.
 * - Timeouts and a small retry budget keep a slow or unreachable store from hanging an upload thread.
 */
@Component
@ConditionalOnProperty(name = "invoice.storage.type", havingValue = "s3")
public class S3DocumentStorage implements InvoiceDocumentStorage, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    static final String SHA256_METADATA = "sha256";
    private static final String EXTENSION = ".pdf";
    private static final String PROBE_FOLDER = "_startup-probe/";
    // S3 bucket naming rules (lower-case letters, digits, dots, hyphens; 3-63 characters).
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern KEY_PREFIX = Pattern.compile("([a-z0-9-]+/)*");

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;
    private final URI endpoint;

    @Autowired
    public S3DocumentStorage(@Value("${invoice.storage.s3.endpoint:}") String endpoint,
                             @Value("${invoice.storage.s3.region:}") String region,
                             @Value("${invoice.storage.s3.bucket:}") String bucket,
                             @Value("${invoice.storage.s3.access-key-id:}") String accessKeyId,
                             @Value("${invoice.storage.s3.secret-access-key:}") String secretAccessKey,
                             @Value("${invoice.storage.s3.key-prefix:invoice-documents/}") String keyPrefix) {
        this(buildClient(endpoint, region, bucket, accessKeyId, secretAccessKey), bucket, keyPrefix, endpoint);
    }

    S3DocumentStorage(S3Client s3, String bucket, String keyPrefix, String endpoint) {
        this.s3 = s3;
        this.bucket = bucket.trim();
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix.trim();
        if (!KEY_PREFIX.matcher(this.keyPrefix).matches()) {
            throw new IllegalStateException("invoice.storage.s3.key-prefix must be empty or like \"invoice-documents/\"");
        }
        this.endpoint = parseEndpoint(endpoint);
        log.info("Invoice PDF storage: private S3-compatible bucket \"{}\" (path-style)", this.bucket);
    }

    /** Validates the settings - naming the missing ENVIRONMENT VARIABLES, never their values - and builds the client. */
    static S3Client buildClient(String endpoint, String region, String bucket, String accessKeyId, String secretAccessKey) {
        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) missing.add("INVOICE_S3_ENDPOINT");
        if (isBlank(region)) missing.add("INVOICE_S3_REGION");
        if (isBlank(bucket)) missing.add("INVOICE_S3_BUCKET");
        if (isBlank(accessKeyId)) missing.add("INVOICE_S3_ACCESS_KEY_ID");
        if (isBlank(secretAccessKey)) missing.add("INVOICE_S3_SECRET_ACCESS_KEY");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("invoice.storage.type=s3 needs these environment variables: "
                    + String.join(", ", missing) + " (docs/RENDER_DEPLOYMENT.md)");
        }
        if (!BUCKET_NAME.matcher(bucket.trim()).matches()) {
            throw new IllegalStateException("INVOICE_S3_BUCKET is not a valid bucket name");
        }
        return S3Client.builder()
                .endpointOverride(parseEndpoint(endpoint))
                .region(Region.of(region.trim()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId.trim(), secretAccessKey.trim())))
                // Path-style ("endpoint/bucket/key") - the only addressing Neon supports. No "aws-chunked" streaming
                // upload either: the body is sent plain with its Content-Length (the PDF is already in memory).
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .apiCallTimeout(Duration.ofSeconds(75))
                        .retryStrategy(b -> b.maxAttempts(3))
                        .build())
                .build();
    }

    /** https only - except a loopback address, which only a test can use. Path, query and user-info are refused. */
    static URI parseEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("INVOICE_S3_ENDPOINT is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (host.isEmpty() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                || !("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
            throw new IllegalStateException("INVOICE_S3_ENDPOINT must be an https:// URL with only a host "
                    + "(e.g. the endpoint shown in the Neon Console)");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority());
    }

    String keyFor(String id) {
        return keyPrefix + InvoiceDocumentStorage.requireDocumentId(id) + EXTENSION;
    }

    @Override
    public StoredDocument store(byte[] bytes) {
        String id = UUID.randomUUID().toString();
        String sha256 = InvoiceDocumentStorage.sha256Hex(bytes);
        String key = keyFor(id);
        try {
            if (head(key) != null) {
                throw new DocumentStorageException("Could not store the original PDF");
            }
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/pdf").contentLength((long) bytes.length)
                            .metadata(Collections.singletonMap(SHA256_METADATA, sha256)),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            deleteQuietly(id);
            throw new DocumentStorageException("Could not store the original PDF", e);
        }

        // Read the object's size and our checksum back, so a short or corrupted write is caught now.
        HeadObjectResponse written;
        try {
            written = head(key);
        } catch (SdkException e) {
            deleteQuietly(id);
            throw new DocumentStorageException("Could not verify the stored PDF", e);
        }
        if (written == null || written.contentLength() == null || written.contentLength() != bytes.length
                || !sha256.equals(written.metadata().get(SHA256_METADATA))) {
            deleteQuietly(id);
            throw new DocumentStorageException("Stored PDF failed its integrity check");
        }
        return new StoredDocument(id, sha256, bytes.length);
    }

    @Override
    public byte[] read(String id, String expectedSha256) {
        String key = keyFor(id);
        byte[] bytes;
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            bytes = object.asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.warn("Stored object for document {} is missing", id);
                throw new DocumentNotFoundException();
            }
            throw new DocumentStorageException("Could not read the stored PDF", e);
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not read the stored PDF", e);
        }
        if (!InvoiceDocumentStorage.sha256Hex(bytes).equals(expectedSha256)) {
            log.error("Stored object for document {} does not match its recorded SHA-256", id);
            throw new DocumentStorageException("Stored PDF failed its integrity check");
        }
        return bytes;
    }

    @Override
    public void deleteQuietly(String id) {
        try {
            String key = keyFor(id);
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            log.error("Could not delete orphaned stored object for document {} ({})", id, describe(e));
        }
    }

    @Override
    public boolean exists(String id) {
        try {
            return head(keyFor(id)) != null;
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not reach document storage", e);
        }
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    /**
     * Write, check (size and SHA-256 metadata, exactly as store() does), read back and delete a small probe object -
     * proves endpoint, region, credentials, write scope and bucket - then make sure the probe could NOT be read
     * without credentials (proves the bucket is private).
     */
    @Override
    public void verifyReady() {
        String key = keyPrefix + PROBE_FOLDER + UUID.randomUUID() + ".txt";
        byte[] content = ("invoice storage probe " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        String sha256 = InvoiceDocumentStorage.sha256Hex(content);
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("text/plain").contentLength((long) content.length)
                            .metadata(Collections.singletonMap(SHA256_METADATA, sha256)),
                    RequestBody.fromBytes(content));
            HeadObjectResponse written = head(key);
            if (written == null || written.contentLength() == null || written.contentLength() != content.length
                    || !sha256.equals(written.metadata().get(SHA256_METADATA))) {
                throw new IllegalStateException("Document storage did not keep the object's size or its sha256 "
                        + "metadata; uploads would fail their integrity check");
            }
            byte[] back = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
            if (!Arrays.equals(back, content)) {
                throw new IllegalStateException("Document storage returned different bytes than written");
            }
            int anonymousStatus = anonymousGetStatus(key);
            if (anonymousStatus >= 200 && anonymousStatus < 300) {
                throw new IllegalStateException("REFUSING to start: the document bucket \"" + bucket + "\" can be read "
                        + "WITHOUT credentials. Set its access level to private in the Neon Console.");
            }
        } catch (SdkException e) {
            throw new IllegalStateException("Document storage is not usable: " + describe(e)
                    + ". Check INVOICE_S3_ENDPOINT, INVOICE_S3_REGION, INVOICE_S3_BUCKET and that the credential has "
                    + "storage:read and storage:write on this branch (docs/RENDER_DEPLOYMENT.md).", e);
        } finally {
            try {
                s3.deleteObject(b -> b.bucket(bucket).key(key));
            } catch (RuntimeException e) {
                log.warn("Could not delete the storage probe object ({})", describe(e));
            }
        }
        log.info("Invoice PDF storage verified: write, read and delete work; anonymous reads are refused");
    }

    /** Plain unauthenticated GET of the object's path-style URL - what anyone on the internet could do. */
    int anonymousGetStatus(String key) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.resolve("/" + bucket + "/" + key).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            return connection.getResponseCode();
        } catch (IOException e) {
            throw new IllegalStateException("Could not check that the document bucket is private ("
                    + e.getClass().getSimpleName() + ")", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** null when the object does not exist. */
    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** HTTP status and S3 error code, or the client-side exception type - never a URL, header or credential. */
    static String describe(Throwable e) {
        if (e instanceof S3Exception) {
            S3Exception s3e = (S3Exception) e;
            String code = s3e.awsErrorDetails() == null ? null : s3e.awsErrorDetails().errorCode();
            return "HTTP " + s3e.statusCode() + (code == null ? "" : " " + code);
        }
        if (e instanceof SdkClientException && e.getCause() != null) {
            return e.getClass().getSimpleName() + " caused by " + e.getCause().getClass().getSimpleName();
        }
        return e.getClass().getSimpleName();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
