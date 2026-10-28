package com.actify.invoice.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real, openable PDFs made in memory for tests - synthetic text only, never a real invoice. The marker text makes
 * every file's bytes (and so its SHA-256) different, which the per-vendor duplicate check needs.
 */
public final class TestPdfs {

    private TestPdfs() {
    }

    /** A one-page PDF whose page shows "Synthetic test invoice " + marker. */
    public static byte[] onePage(String marker) {
        return build(marker, null);
    }

    /** Opens only with the given user password (PdfFiles must refuse it). */
    public static byte[] passwordProtected(String marker, String userPassword) {
        return build(marker, userPassword);
    }

    private static byte[] build(String marker, String userPassword) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Synthetic test invoice " + marker);
                content.endText();
            }
            document.getDocumentInformation().setTitle("synthetic " + marker);
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + userPassword, userPassword,
                        new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
