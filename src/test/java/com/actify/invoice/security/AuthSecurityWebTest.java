package com.actify.invoice.security;

import com.actify.invoice.controller.AdminUserController;
import com.actify.invoice.controller.AuthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.AppUserService;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.Base64URL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login flow and the security filter chain, with the real SecurityConfig, Bearer JWT filter, BCrypt and login
 * limiter. Only the database is replaced by a Mockito repository.
 */
@WebMvcTest(controllers = {AuthController.class, AdminUserController.class})
@WithInvoiceWebSecurity
@Import({LoginService.class, LoginAttemptLimiter.class, AppUserService.class})
class AuthSecurityWebTest {

    private static final String PASSWORD = "correct horse battery staple";
    // Every test uses its own client address, so the shared in-memory login limiter cannot leak between tests.
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService tokenService;

    @MockBean
    private AppUserRepository users;

    @MockBean
    private InvoiceRepository invoiceRepository;

    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------ login

    @Test
    void successfulLoginReturnsABearerTokenInTheBodyAndSetsNoCookie() throws Exception {
        AppUser user = storedUser(11, Role.VENDOR, PASSWORD);

        long before = Instant.now().getEpochSecond();
        MvcResult result = mvc.perform(login(user.getEmail().toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.id").value(11))
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.user.role").value("VENDOR"))
                .andExpect(jsonPath("$.user.name").exists())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.tokenVersion").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).as("no cookie at all").isEmpty();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String token = body.get("accessToken").asText();
        assertThat(token.split("\\.")).hasSize(3);
        long expiresAt = Instant.parse(body.get("expiresAt").asText()).getEpochSecond();
        assertThat(expiresAt).isBetween(before + 1790, before + 1810);

        // The issued token really works on the next request - as a Bearer header.
        mvc.perform(get("/api/auth/me").with(TestLogins.bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("VENDOR"));
    }

    @Test
    void loginNeedsNoCsrfTokenAndIgnoresAStaleAuthorizationHeader() throws Exception {
        storedUser(12, Role.VENDOR, PASSWORD);
        mvc.perform(login("vendor12@example.test", PASSWORD).with(TestLogins.bearer("expired-or-garbage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountAllGetTheSameGeneric401() throws Exception {
        AppUser disabled = storedUser(13, Role.VENDOR, PASSWORD);
        disabled.setEnabled(false);
        storedUser(14, Role.VENDOR, PASSWORD);

        String wrongPassword = mvc.perform(login("vendor14@example.test", "wrong password!!")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(login("nobody@example.test", PASSWORD)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String disabledAccount = mvc.perform(login("vendor13@example.test", PASSWORD)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        for (String body : new String[]{wrongPassword, unknown, disabledAccount}) {
            assertThat(body).contains("\"message\":\"Invalid email or password.\"").doesNotContain("disabled")
                    .doesNotContain("accessToken");
        }
    }

    /** Real e-mail domains work: there is no synthetic-address rule outside tests. */
    @Test
    void anAccountWithARealEmailDomainCanLogIn() throws Exception {
        AppUser user = new AppUser("accounts.team@company-example.in", "Accounts", passwordEncoder.encode(PASSWORD), Role.VENDOR);
        user.setIdForTest(17L);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(17L)).thenReturn(Optional.of(user));
        mvc.perform(login("accounts.team@company-example.in", PASSWORD)).andExpect(status().isOk());
    }

    /** CVE-2025-22228: BCrypt only reads 72 bytes. A longer password sharing those 72 bytes must NOT log in. */
    @Test
    void passwordLongerThan72BytesIsRejectedEvenIfItsFirst72BytesMatch() throws Exception {
        String seventyTwo = repeat("a", 72);
        storedUser(15, Role.ADMIN, seventyTwo);

        mvc.perform(login("admin15@example.test", seventyTwo + "anything")).andExpect(status().isUnauthorized());
        mvc.perform(login("admin15@example.test", repeat("é", 37))).andExpect(status().isUnauthorized()); // 74 bytes
        mvc.perform(login("admin15@example.test", seventyTwo)).andExpect(status().isOk());
    }

    @Test
    void fiveFailuresLockTheEmailEvenForTheCorrectPassword() throws Exception {
        storedUser(16, Role.VENDOR, PASSWORD);
        for (int i = 0; i < 5; i++) {
            mvc.perform(login("vendor16@example.test", "wrong password " + i)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("vendor16@example.test", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many failed login attempts. Try again in 15 minutes."));
    }

    // ------------------------------------------------------------------ the token on later requests

    @Test
    void meNeedsAValidBearerTokenAndReadsTheRoleFromTheDatabase() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        AppUser user = TestLogins.user(20, Role.VENDOR);
        RequestPostProcessor bearer = TestLogins.bearerFor(user, users, tokenService);
        user.setRole(Role.REVIEWER); // database changed after the token was issued (without a version bump)

        mvc.perform(get("/api/auth/me").with(bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test
    void badTokensAreRejectedWith401AndAGenericMessage() throws Exception {
        AppUser user = TestLogins.user(21, Role.ADMIN);
        String valid = TestLogins.tokenFor(user, users, tokenService);
        String[] parts = valid.split("\\.");

        String tampered = parts[0] + "." + Base64URL.encode("{\"sub\":\"21\",\"tv\":0}") + "." + parts[2];
        String expired = tokenService.issue(user, Instant.now().minusSeconds(7200));
        String algNone = Base64URL.encode("{\"alg\":\"none\"}") + "." + parts[1] + ".";
        String otherKey = new JwtTokenService(Base64.getEncoder().encodeToString(new byte[48]), 30).issue(user);
        String hs512 = signRaw(JWSAlgorithm.HS512, parts[1], new byte[64]);

        for (String bad : new String[]{tampered, expired, algNone, otherKey, hs512, "garbage"}) {
            String body = mvc.perform(get("/api/auth/me").with(TestLogins.bearer(bad)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Please log in."))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContainIgnoringCase("expired").doesNotContainIgnoringCase("signature");
        }
    }

    @Test
    void malformedAuthorizationHeadersAreRejected() throws Exception {
        AppUser user = TestLogins.user(27, Role.ADMIN);
        String valid = TestLogins.tokenFor(user, users, tokenService);
        String[] malformed = {"Bearer", "Bearer ", "Bearer " + valid + " extra", "Bearer:" + valid, "Bearer " + valid + ".x"};
        for (String value : malformed) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, value))
                    .andExpect(status().isUnauthorized());
        }
        // Another scheme is simply not a credential here.
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic " + valid))
                .andExpect(status().isUnauthorized());
        // The scheme name is case-insensitive (RFC 6750).
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + valid))
                .andExpect(status().isOk());
    }

    /** The header is the ONLY accepted place: a valid token in a cookie, the URL or the body authenticates nothing. */
    @Test
    void aValidTokenOutsideTheAuthorizationHeaderIsIgnored() throws Exception {
        AppUser user = TestLogins.user(28, Role.ADMIN);
        String valid = TestLogins.tokenFor(user, users, tokenService);

        for (String cookieName : new String[]{"INVOICE_PROCESS_AUTH", "TASK8_AUTH", "access_token", "Authorization"}) {
            mvc.perform(get("/api/auth/me").cookie(new Cookie(cookieName, valid))).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me").param("access_token", valid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me?token=" + valid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"" + valid + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserAndRevokedTokenVersionAreRejectedOnTheNextRequest() throws Exception {
        AppUser user = TestLogins.user(22, Role.REVIEWER);
        RequestPostProcessor bearer = TestLogins.bearerFor(user, users, tokenService);
        mvc.perform(get("/api/auth/me").with(bearer)).andExpect(status().isOk());

        user.bumpTokenVersion(); // e.g. role change or "log out everywhere"
        mvc.perform(get("/api/auth/me").with(bearer)).andExpect(status().isUnauthorized());

        AppUser other = TestLogins.user(23, Role.REVIEWER);
        RequestPostProcessor otherBearer = TestLogins.bearerFor(other, users, tokenService);
        other.setEnabled(false);
        mvc.perform(get("/api/auth/me").with(otherBearer)).andExpect(status().isUnauthorized());

        when(users.findById(24L)).thenReturn(Optional.empty()); // deleted / unknown user id
        mvc.perform(get("/api/auth/me").with(TestLogins.bearer(tokenService.issue(TestLogins.user(24, Role.ADMIN)))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * After the rename to "Actify Invoice Process": a correctly signed token that carries the old issuer or the old
     * audience is refused. The control request with the current identity proves the refusal comes from the
     * issuer/audience check alone.
     */
    @Test
    void wrongIssuerOrAudienceIsRejected() throws Exception {
        TestLogins.tokenFor(TestLogins.user(26, Role.ADMIN), users, tokenService);
        byte[] key = Base64.getDecoder().decode(TestLogins.TEST_SECRET);
        String[][] wrongIdentities = {{"task8-invoice", "task8-web"}, {"task8-invoice", JwtTokenService.AUDIENCE},
                {JwtTokenService.ISSUER, "task8-web"}, {"someone-else", JwtTokenService.AUDIENCE}};
        for (String[] identity : wrongIdentities) {
            String token = signRaw(JWSAlgorithm.HS256, claims(26, identity[0], identity[1]), key);
            mvc.perform(get("/api/auth/me").with(TestLogins.bearer(token))).andExpect(status().isUnauthorized());
        }

        String current = signRaw(JWSAlgorithm.HS256, claims(26, "actify-invoice-process", "actify-invoice-process-web"), key);
        mvc.perform(get("/api/auth/me").with(TestLogins.bearer(current))).andExpect(status().isOk());
    }

    private static String claims(long userId, String issuer, String audience) {
        long now = Instant.now().getEpochSecond();
        return Base64URL.encode("{\"sub\":\"" + userId + "\",\"iss\":\"" + issuer + "\",\"aud\":[\"" + audience
                + "\"],\"tv\":0,\"iat\":" + now + ",\"exp\":" + (now + 600) + "}").toString();
    }

    /** There is no cookie to clear any more: the old single-browser logout endpoint no longer exists. */
    @Test
    void theOldCookieLogoutEndpointIsGone() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
        RequestPostProcessor bearer = TestLogins.bearerFor(TestLogins.user(29, Role.VENDOR), users, tokenService);
        mvc.perform(post("/api/auth/logout").with(bearer)).andExpect(status().isForbidden());
    }

    @Test
    void logoutEverywhereRevokesEveryTokenAlreadyIssued() throws Exception {
        AppUser user = TestLogins.user(25, Role.VENDOR);
        String first = TestLogins.tokenFor(user, users, tokenService);
        String second = tokenService.issue(user); // e.g. another browser

        mvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout-all").with(TestLogins.bearer(first))).andExpect(status().isNoContent());

        assertThat(user.getTokenVersion()).isEqualTo(1);
        mvc.perform(get("/api/auth/me").with(TestLogins.bearer(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(TestLogins.bearer(second))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ route rules

    @Test
    void adminUserApiIsAdminOnlyAndWritesWorkWithBearerAlone() throws Exception {
        RequestPostProcessor vendor = TestLogins.bearerFor(TestLogins.user(30, Role.VENDOR), users, tokenService);
        RequestPostProcessor reviewer = TestLogins.bearerFor(TestLogins.user(31, Role.REVIEWER), users, tokenService);
        RequestPostProcessor admin = TestLogins.bearerFor(TestLogins.user(32, Role.ADMIN), users, tokenService);
        String body = "{\"email\":\"new.vendor@company-example.in\",\"displayName\":\"New\",\"role\":\"VENDOR\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").with(vendor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(reviewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(admin)).andExpect(status().isOk());

        // A write without a token is a 401 (not a CSRF 403); a write with a non-admin token is a 403.
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users").with(vendor).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // No CSRF header anywhere: the Bearer header alone authorizes the write.
        when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.vendor@company-example.in"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String rename = "{\"displayName\":\"Sam\"}";
        mvc.perform(put("/api/admin/users/30/display-name").with(vendor).contentType(MediaType.APPLICATION_JSON)
                .content(rename)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/30/display-name").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(rename)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sam"))
                .andExpect(jsonPath("$.name").value("Sam"));
    }

    /** No privilege escalation: a vendor or reviewer cannot promote, demote, enable or disable anyone - itself included. */
    @Test
    void vendorAndReviewerCannotChangeAnyRoleOrEnabledState() throws Exception {
        AppUser vendorUser = TestLogins.user(50, Role.VENDOR);
        AppUser reviewerUser = TestLogins.user(51, Role.REVIEWER);
        AppUser otherVendor = TestLogins.user(52, Role.VENDOR);
        AppUser adminUser = TestLogins.user(53, Role.ADMIN);
        RequestPostProcessor vendor = TestLogins.bearerFor(vendorUser, users, tokenService);
        RequestPostProcessor reviewer = TestLogins.bearerFor(reviewerUser, users, tokenService);
        when(users.findById(52L)).thenReturn(Optional.of(otherVendor));
        when(users.findById(53L)).thenReturn(Optional.of(adminUser));
        AppUser[] everyone = {vendorUser, reviewerUser, otherVendor, adminUser};
        int[] tokenVersions = new int[everyone.length];
        for (int i = 0; i < everyone.length; i++) {
            tokenVersions[i] = everyone[i].getTokenVersion();
        }

        for (RequestPostProcessor caller : new RequestPostProcessor[]{vendor, reviewer}) {
            for (AppUser target : everyone) {
                String role = target.getRole() == Role.ADMIN ? "VENDOR" : "ADMIN";
                mvc.perform(put("/api/admin/users/" + target.getId() + "/role").with(caller)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                        .andExpect(status().isForbidden());
                mvc.perform(put("/api/admin/users/" + target.getId() + "/enabled").with(caller)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                        .andExpect(status().isForbidden());
            }
        }

        assertThat(vendorUser.getRole()).isEqualTo(Role.VENDOR);
        assertThat(reviewerUser.getRole()).isEqualTo(Role.REVIEWER);
        assertThat(otherVendor.getRole()).isEqualTo(Role.VENDOR);
        assertThat(adminUser.getRole()).isEqualTo(Role.ADMIN);
        for (int i = 0; i < everyone.length; i++) {
            assertThat(everyone[i].isEnabled()).as("user %s still enabled", everyone[i].getId()).isTrue();
            assertThat(everyone[i].getTokenVersion()).as("user %s tokens untouched", everyone[i].getId())
                    .isEqualTo(tokenVersions[i]);
        }
        // The callers' own tokens still work: nothing was revoked by the refused requests.
        mvc.perform(get("/api/auth/me").with(vendor)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VENDOR"));
        mvc.perform(get("/api/auth/me").with(reviewer)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    /** Control for the test above: the same requests from an ADMIN reach the controller and take effect. */
    @Test
    void adminCanChangeRoleAndEnabledStateWithTheSameRequests() throws Exception {
        AppUser target = TestLogins.user(55, Role.VENDOR);
        when(users.findById(55L)).thenReturn(Optional.of(target));
        RequestPostProcessor admin = TestLogins.bearerFor(TestLogins.user(56, Role.ADMIN), users, tokenService);

        mvc.perform(put("/api/admin/users/55/role").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("REVIEWER"));
        mvc.perform(put("/api/admin/users/55/enabled").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(target.getRole()).isEqualTo(Role.REVIEWER);
        assertThat(target.isEnabled()).isFalse();
    }

    @Test
    void anythingNotListedIsDenied() throws Exception {
        RequestPostProcessor admin = TestLogins.bearerFor(TestLogins.user(40, Role.ADMIN), users, tokenService);
        mvc.perform(get("/api/not-a-route")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/not-a-route").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/invoices/extract/other").with(admin)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private AppUser storedUser(long id, Role role, String password) {
        AppUser user = new AppUser(role.name().toLowerCase() + id + "@example.test", "User " + id,
                passwordEncoder.encode(password), role);
        user.setIdForTest(id);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(address());
    }

    private static RequestPostProcessor address() {
        String address = "10.20.0." + NEXT_ADDRESS.getAndIncrement();
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String signRaw(JWSAlgorithm alg, String payloadPart, byte[] secret) throws Exception {
        JWSHeader header = new JWSHeader(alg);
        String signingInput = header.toBase64URL() + "." + payloadPart;
        return signingInput + "." + new MACSigner(secret).sign(header, signingInput.getBytes(StandardCharsets.US_ASCII));
    }

    private static String repeat(String s, int times) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < times; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
