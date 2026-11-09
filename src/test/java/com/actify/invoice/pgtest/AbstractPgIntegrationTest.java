package com.actify.invoice.pgtest;

import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.security.JwtTokenService;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserService;
import com.actify.invoice.user.Role;
import com.actify.invoice.versioning.InvoiceVersionWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for tests that need a real PostgreSQL. Runs only with "mvnw test -Ppgtest".
 *
 * Safety:
 *  - profile "pgtest" -> application-pgtest.properties -> the separate *_test_db database;
 *  - SchemaSafetyGuard allows ddl-auto=update only for this profile + this exact URL;
 *  - PgTestDatabaseGuard verifies profile + SELECT current_database() before Hibernate runs any DDL;
 *  - every test re-checks current_database() before it starts;
 *  - the LLM extractor is a Mockito mock: no Gemini call, no real invoice leaves the machine;
 *  - PDFs go to a fresh folder under target/, never the real document storage folder;
 *  - tests only INSERT their own uniquely named rows; they never delete, truncate or drop anything.
 *
 * Login: every HTTP call made with TestRestTemplate is sent as a TEST-DATABASE admin (a real app_users row
 * created once per run with a unique email), with a real signed "Authorization: Bearer" header.
 * Use actAs(...) / actAnonymously() inside a test to call the API as another user.
 */
@Tag("pgtest")
@ActiveProfiles(PgTestDatabaseGuard.REQUIRED_PROFILE)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PgTestDatabaseGuard.Config.class)
public abstract class AbstractPgIntegrationTest {

    protected static final Path STORAGE_DIR = Paths.get("target", "pgtest-documents", UUID.randomUUID().toString())
            .toAbsolutePath().normalize();

    /** Test-only password of the users this test run creates in the TEST database. */
    protected static final String TEST_PASSWORD = "pgtest password " + UUID.randomUUID().toString().substring(0, 8);

    private static AppUser admin;

    @DynamicPropertySource
    static void storageDir(DynamicPropertyRegistry registry) {
        registry.add("invoice.storage.dir", STORAGE_DIR::toString);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected AppUserService userService;

    @Autowired
    protected JwtTokenService tokenService;

    @MockBean
    protected LlmInvoiceExtractor llmInvoiceExtractor;

    // A spy (real behaviour unless a test stubs it) so rollback tests can make the version insert fail.
    @SpyBean
    protected InvoiceVersionWriter versionWriter;

    @BeforeEach
    void confirmTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo(PgTestDatabaseGuard.TEST_DATABASE);
    }

    @BeforeEach
    void loginAsTestAdmin() {
        if (admin == null) {
            admin = createUser(Role.ADMIN);
        }
        actAs(admin);
    }

    /** A new user in the TEST database, with a unique email and TEST_PASSWORD. */
    protected AppUser createUser(Role role) {
        String email = "pgtest-" + role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return userService.createUser(email, "PG test " + role.name(), role, TEST_PASSWORD);
    }

    protected AppUser testAdmin() {
        return admin;
    }

    /** From now on, TestRestTemplate calls are made as this user (fresh token for its current token version). */
    protected void actAs(AppUser user) {
        authInterceptor().token = tokenService.issue(user);
    }

    /** From now on, TestRestTemplate calls carry no Authorization header. */
    protected void actAnonymously() {
        authInterceptor().token = null;
    }

    @Autowired
    private com.actify.invoice.storage.InvoiceDocumentStorage seedDocumentStorage;

    @Autowired
    private com.actify.invoice.repository.InvoiceDocumentRepository seedDocumentRepository;

    @Autowired
    private com.actify.invoice.repository.InvoiceRepository seedInvoiceRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager seedTransactionManager;

    /**
     * TEST-ONLY seed of a LEGACY invoice (no owner, no batch, status EXTRACTED, V1, stored PDF) - the rows the removed
     * direct-extraction endpoint used to write. Historical rows like these still exist in real databases, so the
     * read/edit/version/document routes keep being tested against them. Nothing is sent to Gemini.
     */
    protected long seedLegacyInvoice(com.actify.invoice.dto.InvoiceExtractionResult result, byte[] pdf, String fileName) {
        com.actify.invoice.storage.StoredDocument stored = seedDocumentStorage.store(pdf);
        return new org.springframework.transaction.support.TransactionTemplate(seedTransactionManager).execute(s -> {
            com.actify.invoice.entity.InvoiceDocument document = seedDocumentRepository.save(
                    new com.actify.invoice.entity.InvoiceDocument(stored.getId(), stored.getSha256(), stored.getSizeBytes(),
                            "application/pdf", fileName));
            com.actify.invoice.entity.Invoice invoice = new com.actify.invoice.entity.Invoice();
            invoice.setSourceFileName(fileName);
            invoice.setSourceFileSize((long) pdf.length);
            invoice.setSourceDocument(document);
            invoice.setStatus(com.actify.invoice.entity.InvoiceStatus.EXTRACTED);
            invoice.setRawLlmResponse(result.getRawResponse());
            invoice.setInvoiceNumber(result.getInvoiceNumber());
            invoice.setInvoiceDate(result.getInvoiceDate() == null ? null : java.time.LocalDate.parse(result.getInvoiceDate()));
            invoice.setSellerName(result.getSellerName());
            invoice.setSellerTaxId(result.getSellerTaxId());
            invoice.setBuyerName(result.getBuyerName());
            invoice.setBuyerTaxId(result.getBuyerTaxId());
            invoice.setCurrency(result.getCurrency());
            invoice.setSubtotal(result.getSubtotal());
            invoice.setDiscountAmount(result.getDiscountAmount());
            invoice.setShippingAmount(result.getShippingAmount());
            invoice.setTaxAmount(result.getTaxAmount());
            invoice.setTotalAmount(result.getTotalAmount());
            for (com.actify.invoice.dto.InvoiceItemDto dto : result.getItems()) {
                com.actify.invoice.entity.InvoiceItem item = new com.actify.invoice.entity.InvoiceItem();
                item.setDescription(dto.getDescription());
                item.setQuantity(dto.getQuantity());
                item.setUnitPrice(dto.getUnitPrice());
                item.setTaxRate(dto.getTaxRate());
                item.setLineTotal(dto.getLineTotal());
                invoice.addItem(item);
            }
            com.actify.invoice.entity.Invoice saved = seedInvoiceRepository.save(invoice);
            versionWriter.recordExtraction(saved);
            return saved.getId();
        });
    }

    /** TEST-ONLY seed of a legacy upload whose extraction FAILED (no document, no version). */
    protected long seedFailedLegacyInvoice(String fileName, String reason) {
        com.actify.invoice.entity.Invoice invoice = new com.actify.invoice.entity.Invoice();
        invoice.setSourceFileName(fileName);
        invoice.setStatus(com.actify.invoice.entity.InvoiceStatus.FAILED);
        invoice.setFailureReason(reason);
        return seedInvoiceRepository.save(invoice).getId();
    }

    private AuthInterceptor authInterceptor() {
        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getRestTemplate().getInterceptors();
        for (ClientHttpRequestInterceptor interceptor : interceptors) {
            if (interceptor instanceof AuthInterceptor) {
                return (AuthInterceptor) interceptor;
            }
        }
        AuthInterceptor created = new AuthInterceptor();
        interceptors.add(created);
        return created;
    }

    /** Adds "Authorization: Bearer <token>" unless the request already sets its own Authorization header. */
    static final class AuthInterceptor implements ClientHttpRequestInterceptor {
        volatile String token;

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String current = token;
            if (current != null && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(current);
            }
            return execution.execute(request, body);
        }
    }
}
