package com.actify.invoice.pgtest;

import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.InvoiceItemDto;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.security.AuthCookies;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Phase 1 against the real TEST database: app_users table, real login, revocation, isolation, changed_by. */
class AuthPgIntegrationTest extends AbstractPgIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appUsersEmailIsUniqueInTheDatabaseItself() {
        AppUser user = createUser(Role.VENDOR);
        assertThat(jdbc.queryForObject("select password_hash from app_users where id = ?", String.class, user.getId()))
                .startsWith("$2a$").doesNotContain(TEST_PASSWORD);

        assertThatThrownBy(() -> jdbc.update("insert into app_users (email, display_name, password_hash, role, enabled, "
                + "token_version, created_at, updated_at) values (?, 'dup', 'x', 'VENDOR', true, 0, now(), now())", user.getEmail()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void realLoginSetsAnHttpOnlyCookieThatAuthenticatesTheNextRequest() throws Exception {
        AppUser vendor = createUser(Role.VENDOR);
        actAnonymously();

        ResponseEntity<String> login = restTemplate.postForEntity("/api/auth/login",
                json("{\"email\":\"" + vendor.getEmail().toUpperCase() + "\",\"password\":\"" + TEST_PASSWORD + "\"}"),
                String.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = login.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(AuthCookies.AUTH_COOKIE + "=")).findFirst().orElseThrow(AssertionError::new);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(login.getBody()).doesNotContain(cookieValue(setCookie));

        ResponseEntity<String> me = withCookie(HttpMethod.GET, "/api/auth/me", cookieValue(setCookie));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(me.getBody()).get("role").asText()).isEqualTo("VENDOR");
        // (Wrong-password 401s are covered in AuthSecurityWebTest: the JDK HTTP client used here cannot read a
        // 401 response to a POST - it throws HttpRetryException instead.)
    }

    @Test
    void disablingAUserThroughTheAdminApiEndsTheirExistingSession() {
        AppUser vendor = createUser(Role.VENDOR);
        String vendorToken = tokenService.issue(vendor);
        assertThat(withCookie(HttpMethod.GET, "/api/auth/me", vendorToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> disable = restTemplate.exchange("/api/admin/users/" + vendor.getId() + "/enabled",
                HttpMethod.PUT, json("{\"enabled\":false}"), String.class);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select token_version from app_users where id = ?", Integer.class, vendor.getId()))
                .isEqualTo(1);

        assertThat(withCookie(HttpMethod.GET, "/api/auth/me", vendorToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void legacyInvoicesAreAdminOnlyAndCorrectionsRecordWhoMadeThem() throws Exception {
        long id = uploadSyntheticInvoice();

        AppUser vendor = createUser(Role.VENDOR);
        actAs(vendor);
        assertThat(restTemplate.getForEntity("/api/invoices/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/invoices/" + id + "/versions", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        String docId = jdbc.queryForObject("select source_document_id from invoices where id = ?", String.class, id);
        assertThat(restTemplate.getForEntity("/api/documents/" + docId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/invoices/" + id, HttpMethod.PUT, json("{\"baseVersion\":1,\"items\":[]}"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        actAs(testAdmin());
        assertThat(restTemplate.getForEntity("/api/documents/" + docId, byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String correction = "{\"baseVersion\":1,\"invoiceNumber\":\"AUTH-CORRECTED\",\"currency\":\"INR\",\"subtotal\":\"100.00\","
                + "\"taxAmount\":\"18.00\",\"totalAmount\":\"118.00\",\"changeReason\":\"pgtest\",\"items\":[{\"description\":\"Item\","
                + "\"quantity\":\"1\",\"unitPrice\":\"100.00\",\"taxRate\":\"18\",\"lineTotal\":\"100.00\"}]}";
        ResponseEntity<String> saved = restTemplate.exchange("/api/invoices/" + id, HttpMethod.PUT, json(correction), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select changed_by from invoice_versions where invoice_id = ? and version_number = 2",
                String.class, id)).isEqualTo("user:" + testAdmin().getId());
    }

    @Test
    void writesWithoutTheCsrfHeaderAreRefusedEvenWithAValidLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, AuthCookies.AUTH_COOKIE + "=" + tokenService.issue(testAdmin()));
        headers.add("X-XSRF-TOKEN", "does-not-match-the-cookie");
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/users", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ helpers

    private long uploadSyntheticInvoice() throws Exception {
        InvoiceExtractionResult r = new InvoiceExtractionResult();
        r.setInvoiceNumber("AUTH-" + UUID.randomUUID().toString().substring(0, 8));
        r.setCurrency("INR");
        r.setSubtotal(new BigDecimal("100.00"));
        r.setTaxAmount(new BigDecimal("18.00"));
        r.setTotalAmount(new BigDecimal("118.00"));
        r.setItems(Collections.singletonList(new InvoiceItemDto("Item", BigDecimal.ONE, new BigDecimal("100.00"),
                new BigDecimal("18"), new BigDecimal("100.00"))));
        r.setRawResponse("{\"synthetic\":true}");
        when(llmInvoiceExtractor.extract(any(byte[].class))).thenReturn(new LlmExtractionResponse(r, null));

        byte[] pdf = ("%PDF-1.4\n% synthetic auth test " + UUID.randomUUID() + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "auth-synthetic.pdf";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/invoices/extract", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode invoice = objectMapper.readTree(response.getBody());
        return invoice.get("id").asLong();
    }

    private ResponseEntity<String> withCookie(HttpMethod method, String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, AuthCookies.AUTH_COOKIE + "=" + token);
        return restTemplate.exchange(url, method, new HttpEntity<>(headers), String.class);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }
}
