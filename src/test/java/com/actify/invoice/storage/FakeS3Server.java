package com.actify.invoice.storage;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny in-memory S3 endpoint on 127.0.0.1 (JDK HttpServer, no extra library) for S3DocumentStorageTest. It speaks
 * the four operations the application uses (PUT, GET, HEAD, DELETE of "/bucket/key", i.e. path-style like Neon), keeps
 * x-amz-meta-* headers, answers S3-style XML errors, refuses unsigned requests like a private bucket, and records every
 * request so tests can check what the real AWS client sent. It does NOT verify signatures: it proves our client
 * configuration and error handling, not a provider account (that is the live smoke test in docs/RENDER_DEPLOYMENT.md).
 */
final class FakeS3Server implements AutoCloseable {

    static final class StoredObject {
        final byte[] bytes;
        final Map<String, String> metadata;

        StoredObject(byte[] bytes, Map<String, String> metadata) {
            this.bytes = bytes;
            this.metadata = metadata;
        }
    }

    static final class Recorded {
        final String method;
        final String path;
        final Map<String, String> headers;

        Recorded(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }

    final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    /** Simulates a store outage / provider error on every PUT. */
    final AtomicBoolean failPuts = new AtomicBoolean();
    /** Simulates a store that silently drops custom metadata. */
    final AtomicBoolean dropMetadata = new AtomicBoolean();
    /** Simulates a bucket that was (wrongly) made public_read. */
    final AtomicBoolean publicRead = new AtomicBoolean();

    private final HttpServer server;

    FakeS3Server() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
                headers.put(h.getKey(), String.join(",", h.getValue()));
            }
            byte[] body = readAll(exchange.getRequestBody());
            requests.add(new Recorded(method, path, headers));

            String auth = headers.get("Authorization");
            boolean signed = auth != null && auth.startsWith("AWS4-HMAC-SHA256 ");
            if (!signed && !(publicRead.get() && "GET".equals(method))) {
                error(exchange, 403, "AccessDenied");
                return;
            }
            switch (method) {
                case "PUT":
                    if (failPuts.get()) {
                        error(exchange, 500, "InternalError");
                        return;
                    }
                    Map<String, String> metadata = new TreeMap<>();
                    if (!dropMetadata.get()) {
                        for (Map.Entry<String, String> h : headers.entrySet()) {
                            String name = h.getKey().toLowerCase(Locale.ROOT);
                            if (name.startsWith("x-amz-meta-")) {
                                metadata.put(name.substring("x-amz-meta-".length()), h.getValue());
                            }
                        }
                    }
                    objects.put(path, new StoredObject(body, metadata));
                    exchange.getResponseHeaders().set("ETag", "\"etag\"");
                    exchange.sendResponseHeaders(200, -1);
                    return;
                case "GET": {
                    StoredObject object = objects.get(path);
                    if (object == null) {
                        error(exchange, 404, "NoSuchKey");
                        return;
                    }
                    putMetadata(exchange.getResponseHeaders(), object);
                    exchange.sendResponseHeaders(200, object.bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(object.bytes);
                    }
                    return;
                }
                case "HEAD": {
                    StoredObject object = objects.get(path);
                    if (object == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    putMetadata(exchange.getResponseHeaders(), object);
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(object.bytes.length));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                case "DELETE":
                    objects.remove(path);
                    exchange.sendResponseHeaders(204, -1);
                    return;
                default:
                    error(exchange, 501, "NotImplemented");
            }
        } finally {
            exchange.close();
        }
    }

    private static void putMetadata(Headers response, StoredObject object) {
        response.set("Content-Type", "application/octet-stream");
        for (Map.Entry<String, String> m : object.metadata.entrySet()) {
            response.set("x-amz-meta-" + m.getKey(), m.getValue());
        }
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + code
                + "</Message><RequestId>fake</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, xml.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(xml);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
