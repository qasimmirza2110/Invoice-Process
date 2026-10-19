package com.actify.invoice.service;

import com.actify.invoice.exception.InvalidInvoiceException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The upload checks for one PDF, shared by the legacy single-file endpoint and the batch intake,
 * so both apply exactly the same rules (OWASP file upload: size, extension, type, real signature).
 */
public final class PdfFiles {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    // Browsers/Postman send application/pdf, but some clients (e.g. curl) send octet-stream.
    // The content type is set by the client and easy to fake, so the real check is the %PDF- signature.
    private static final Set<String> ALLOWED_CONTENT_TYPES = new HashSet<>(
            Arrays.asList("application/pdf", "application/x-pdf", "application/octet-stream"));

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};

    /** Stored for every document: only files that passed the %PDF- signature check are ever stored. */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private PdfFiles() {
    }

    /** @throws InvalidInvoiceException with a client-safe message when the file is not an acceptable PDF */
    public static byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidInvoiceException("No file uploaded or the file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidInvoiceException("File is too large. Maximum size is 10 MB.");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            throw new InvalidInvoiceException("Only .pdf files are accepted");
        }

        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidInvoiceException("Unsupported content type '" + contentType + "'. Upload a PDF.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidInvoiceException("Could not read the uploaded file");
        }

        if (!startsWithPdfSignature(bytes)) {
            throw new InvalidInvoiceException("File content is not a valid PDF");
        }
        return bytes;
    }

    private static boolean startsWithPdfSignature(byte[] bytes) {
        if (bytes.length < PDF_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PDF_SIGNATURE.length; i++) {
            if (bytes[i] != PDF_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The name is only stored as metadata (never used as a file path), but we still strip
     * any directory part and odd characters a client might send.
     */
    public static String safeFileName(String originalFileName) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(originalFileName == null ? "" : originalFileName));
        if (name == null || name.isEmpty()) {
            return "invoice.pdf";
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
