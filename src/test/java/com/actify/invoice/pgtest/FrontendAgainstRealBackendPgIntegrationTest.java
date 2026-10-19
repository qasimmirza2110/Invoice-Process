package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.intake.CategoryCodes;
import com.actify.invoice.review.ReviewAssignmentService;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Runs the browser test (src/test/frontend/invoice-review-ui.e2e.mjs, "real" mode) against this
 * application instance: real controllers, services, Hibernate and the PostgreSQL TEST database,
 * with only the LLM mocked. Skipped (not failed) when Node.js or a Chrome/Edge browser is missing.
 *
 * Besides the legacy review UI, the browser also walks the whole batch workflow through the role pages:
 * vendor upload -> admin assignment -> reviewer correction + submit -> admin approval. While the browser runs,
 * a background thread plays the extraction worker (disabled as a scheduled job in tests).
 */
class FrontendAgainstRealBackendPgIntegrationTest extends AbstractQueuePgTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CategoryCodes categoryCodes;

    @Autowired
    private ReviewAssignmentService assignments;

    @Test
    void reviewUiWorksEndToEndAgainstTheRealBackend() throws Exception {
        assumeTrue(commandWorks("node", "--version"), "Node.js is not installed - browser test skipped");
        drainQueue();

        // Users and routing data of THIS run (unique names, test database only).
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser vendor = createUser(Role.VENDOR);
        AppUser reviewer = userService.createUser("ui-reviewer-" + suffix + "@example.test", "UI Reviewer " + suffix,
                Role.REVIEWER, TEST_PASSWORD);
        String category = "UI_" + suffix.toUpperCase();
        categoryCodes.create(category, "UI test " + suffix);
        assignments.setReviewerCategories(reviewer.getId(), Collections.singletonList(category));

        // The legacy UI test expects invoice 000000005 with consistent totals; the workflow upload gets a wrong
        // total so the reviewer has something to correct. The mock tells them apart by the synthetic PDF's name.
        doAnswer(invocation -> {
            String pdf = new String((byte[]) invocation.getArgument(0), StandardCharsets.US_ASCII);
            InvoiceExtractionResult result = legacySynthetic();
            if (pdf.contains("workflow-synthetic")) {
                result.setInvoiceNumber("UI-" + suffix);
                result.setSellerName("Synthetic Workflow Seller " + suffix);
                result.setTotalAmount(new BigDecimal("99999.00"));
            }
            return new LlmExtractionResponse(result, null);
        }).when(llmInvoiceExtractor).extract(any(byte[].class));

        AtomicBoolean browserRunning = new AtomicBoolean(true);
        Thread workerThread = new Thread(() -> {
            while (browserRunning.get()) {
                try {
                    if (!worker.processNextJob()) {
                        Thread.sleep(300);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.out.println("[worker] " + e);
                }
            }
        }, "test-extraction-worker");
        workerThread.start();

        ProcessBuilder builder = new ProcessBuilder("node", "src/test/frontend/invoice-review-ui.e2e.mjs",
                "--base=http://127.0.0.1:" + port)
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true);
        // The browser logs in through login.html as these TEST-database users of this run.
        builder.environment().put("TASK8_E2E_EMAIL", testAdmin().getEmail());
        builder.environment().put("TASK8_E2E_PASSWORD", TEST_PASSWORD);
        builder.environment().put("TASK8_E2E_VENDOR_EMAIL", vendor.getEmail());
        builder.environment().put("TASK8_E2E_REVIEWER_EMAIL", reviewer.getEmail());
        builder.environment().put("TASK8_E2E_REVIEWER_NAME", reviewer.getDisplayName());
        builder.environment().put("TASK8_E2E_CATEGORY", category);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                System.out.println("[ui] " + line);
            }
        } finally {
            browserRunning.set(false);
            workerThread.join(10000);
        }
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
        assumeTrue(!output.toString().contains("No Chrome/Edge found"), "No Chrome/Edge browser - browser test skipped");
        assertThat(process.exitValue()).as(output.toString()).isZero();
        assertThat(output.toString()).contains("0 failed").contains("[workflow] admin reviews the V1 -> V2 changes");
    }

    private static InvoiceExtractionResult legacySynthetic() {
        InvoiceExtractionResult synthetic = new InvoiceExtractionResult();
        synthetic.setInvoiceNumber("000000005");
        synthetic.setInvoiceDate("2026-10-06");
        synthetic.setSellerName("Synthetic UI Seller");
        synthetic.setCurrency("INR");
        synthetic.setSubtotal(new BigDecimal("10500.00"));
        synthetic.setTaxAmount(new BigDecimal("1890.00"));
        synthetic.setTotalAmount(new BigDecimal("12390.00"));
        synthetic.setItems(Arrays.asList(
                new InvoiceItemDto("Laptop Stand", new BigDecimal("2"), new BigDecimal("5000.00"),
                        new BigDecimal("18"), new BigDecimal("10000.00")),
                new InvoiceItemDto("USB-C Cable", new BigDecimal("1"), new BigDecimal("500.00"),
                        new BigDecimal("18"), new BigDecimal("500.00"))));
        synthetic.setRawResponse("{\"synthetic\":true}");
        return synthetic;
    }

    private boolean commandWorks(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
