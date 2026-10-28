package com.actify.invoice.service;

import com.actify.invoice.exception.InvalidInvoiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload checks applied to every PDF (batch upload and legacy endpoint). Any openable PDF of an acceptable size is
 * accepted, whatever its name or content - there is no list of allowed files.
 */
class PdfFilesTest {

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    @Test
    void anyReadablePdfIsAcceptedRegardlessOfItsNameOrContent() {
        byte[] pdf = TestPdfs.onePage("ACME-2026-0042");
        assertThat(PdfFiles.validateAndRead(file("Supplier Invoice Oct-2026.pdf", "application/pdf", pdf))).isEqualTo(pdf);
        // Some clients (curl, some cloud downloads) send a generic type; the content check is what counts.
        assertThat(PdfFiles.validateAndRead(file("scan.PDF", "application/octet-stream", pdf))).isEqualTo(pdf);
        assertThat(PdfFiles.validateAndRead(file("x.pdf", null, pdf))).isEqualTo(pdf);
    }

    @Test
    void aFileThatOnlyStartsWithThePdfMagicBytesIsRejected() {
        byte[] fake = "%PDF-1.4\n% not really a PDF\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("fake.pdf", "application/pdf", fake)))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessage("The file could not be opened as a PDF (damaged or incomplete)");
    }

    @Test
    void aTruncatedPdfIsRejected() {
        byte[] pdf = TestPdfs.onePage("truncated");
        byte[] half = new byte[pdf.length / 3];
        System.arraycopy(pdf, 0, half, 0, half.length);
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("half.pdf", "application/pdf", half)))
                .isInstanceOf(InvalidInvoiceException.class);
    }

    @Test
    void aPasswordProtectedPdfIsRejectedWithAnActionableMessage() {
        byte[] locked = TestPdfs.passwordProtected("locked", "open-sesame");
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("locked.pdf", "application/pdf", locked)))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("Password-protected");
    }

    @Test
    void wrongExtensionTypeSignatureEmptyAndOversizeAreRejected() {
        byte[] pdf = TestPdfs.onePage("x");
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("invoice.exe", "application/pdf", pdf)))
                .hasMessage("Only .pdf files are accepted");
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("invoice.pdf", "text/html", pdf)))
                .hasMessageContaining("Unsupported content type");
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("invoice.pdf", "application/pdf",
                "<html>hello</html>".getBytes(StandardCharsets.US_ASCII)))).hasMessage("File content is not a valid PDF");
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("invoice.pdf", "application/pdf", new byte[0])))
                .hasMessage("No file uploaded or the file is empty");
        byte[] big = new byte[(int) PdfFiles.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(pdf, 0, big, 0, pdf.length);
        assertThatThrownBy(() -> PdfFiles.validateAndRead(file("big.pdf", "application/pdf", big)))
                .hasMessage("File is too large. Maximum size is 10 MB.");
    }

    /** The six samples handed to the React developer must themselves pass the upload checks. */
    @Test
    void theSyntheticSamplePdfsInTheHandoffAreAccepted() throws Exception {
        java.nio.file.Path folder = java.nio.file.Paths.get("docs", "react-handoff", "sample-invoices");
        for (int i = 1; i <= 6; i++) {
            String name = "synthetic-sample-invoice-0" + i + ".pdf";
            byte[] bytes = java.nio.file.Files.readAllBytes(folder.resolve(name));
            assertThat(PdfFiles.validateAndRead(file(name, "application/pdf", bytes))).as(name).isEqualTo(bytes);
        }
    }

    @Test
    void theClientFileNameIsReducedToASafeLabel() {
        assertThat(PdfFiles.safeFileName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(PdfFiles.safeFileName("C:\\Users\\me\\inv<1>.pdf")).doesNotContain("\\").doesNotContain("<");
        assertThat(PdfFiles.safeFileName(null)).isEqualTo("invoice.pdf");
    }
}
