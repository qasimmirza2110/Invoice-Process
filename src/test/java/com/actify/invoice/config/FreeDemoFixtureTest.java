package com.actify.invoice.config;

import com.actify.invoice.service.PdfFiles;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free demo accepts only the published fixture PDFs. Keeps the committed files in
 * docs/react-handoff/demo-fixtures/ and the SHA-256 list in application-render-free-demo.properties identical, and
 * proves every fixture is visibly synthetic and passes the normal production PDF checks after the allowlist.
 */
class FreeDemoFixtureTest {

    private static final Path FIXTURES = Paths.get("docs", "react-handoff", "demo-fixtures");

    private static List<Path> fixtures() throws Exception {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".pdf")).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void theAllowlistIsExactlyTheCommittedFixtures() throws Exception {
        Properties demo = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-" + RenderFreeDemoGuard.PROFILE + ".properties"));
        Set<String> allowlisted = new TreeSet<>(FreeDemoUploadPolicy.parse(
                demo.getProperty(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY)));
        Set<String> committed = new TreeSet<>();
        for (Path fixture : fixtures()) {
            committed.add(InvoiceDocumentStorage.sha256Hex(Files.readAllBytes(fixture)));
        }
        assertThat(fixtures()).hasSize(6);
        assertThat(allowlisted).hasSize(6).isEqualTo(committed);
    }

    @Test
    void everyFixtureIsVisiblySyntheticAndPassesTheNormalPdfChecks() throws Exception {
        for (Path fixture : fixtures()) {
            byte[] bytes = Files.readAllBytes(fixture);
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(text).as(fixture.toString())
                    .startsWith("%PDF-1.4")
                    .contains("SYNTHETIC DEMO INVOICE - NOT A REAL DOCUMENT", "Contains no real customer data",
                            "Synthetic Seller", "Synthetic Buyer")
                    .endsWith("%%EOF\n");
            assertThat(bytes.length).isLessThan(4096);
            MockMultipartFile upload = new MockMultipartFile("file", fixture.getFileName().toString(),
                    "application/pdf", bytes);
            assertThat(PdfFiles.validateAndRead(upload)).isEqualTo(bytes);
        }
    }
}
