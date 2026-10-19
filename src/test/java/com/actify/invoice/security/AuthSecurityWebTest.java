package com.actify.invoice.security;

import com.actify.invoice.controller.AdminUserController;
import com.actify.invoice.controller.AuthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.AppUserService;
import com.actify.invoice.user.Role;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login flow and the security filter chain, with the real SecurityConfig, JWT filter, CSRF, BCrypt and
 * login limiter. Only the database is replaced by a Mockito repository.
 */
@WebMvcTest(controllers = {AuthController.class, AdminUserController.class})
@WithTask8WebSecurity
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

    // ------------------------------------------------------------------ login

    @Test
    void successfulLoginSetsAHardenedCookieAndNeverReturnsTheToken() throws Exception {
        AppUser user = storedUser(11, Role.VENDOR, PASSWORD);

        MvcResult result = mvc.perform(login(user.getEmail().toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.role").value("VENDOR"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();

        String setCookie = authSetCookie(result);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .contains("Max-Age=1800");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(cookieValue(setCookie));
        // The issued cookie really works on the next request.
        mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthCookies.AUTH_COOKIE, cookieValue(setCookie))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        storedUser(12, Role.VENDOR, PASSWORD);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"vendor12@example.test\",\"password\":\"" + PASSWORD + "\"}").with(address()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Missing or invalid CSRF token. Reload the page and try again."));
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
            assertThat(body).contains("\"message\":\"Invalid email or password.\"").doesNotContain("disabled");
        }
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

    // ------------------------------------------------------------------ the cookie on later requests

    @Test
    void meNeedsAValidCookieAndReadsTheRoleFromTheDatabase() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        AppUser user = TestLogins.user(20, Role.VENDOR);
        Cookie cookie = TestLogins.cookieFor(user, users, tokenService);
        user.setRole(Role.REVIEWER); // database changed after the token was issued (without a version bump)

        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test
    void badCookiesAreRejectedWith401AndDeleted() throws Exception {
        AppUser user = TestLogins.user(21, Role.ADMIN);
        String valid = TestLogins.cookieFor(user, users, tokenService).getValue();
        String[] parts = valid.split("\\.");

        String tampered = parts[0] + "." + Base64URL.encode("{\"sub\":\"21\",\"tv\":0}") + "." + parts[2];
        String expired = tokenService.issue(user, Instant.now().minusSeconds(7200));
        String algNone = Base64URL.encode("{\"alg\":\"none\"}") + "." + parts[1] + ".";
        String otherKey = new JwtTokenService(Base64.getEncoder().encodeToString(new byte[48]), 30).issue(user);
        String hs512 = signRaw(JWSAlgorithm.HS512, parts[1], new byte[64]);

        for (String bad : new String[]{tampered, expired, algNone, otherKey, hs512, "garbage"}) {
            MvcResult result = mvc.perform(get("/api/auth/me").cookie(new Cookie(AuthCookies.AUTH_COOKIE, bad)))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(authSetCookie(result)).as("cookie deleted").contains("Max-Age=0");
        }
    }

    @Test
    void disabledUserAndRevokedTokenVersionAreRejectedOnTheNextRequest() throws Exception {
        AppUser user = TestLogins.user(22, Role.REVIEWER);
        Cookie cookie = TestLogins.cookieFor(user, users, tokenService);
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());

        user.bumpTokenVersion(); // e.g. role change or "log out everywhere"
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());

        AppUser other = TestLogins.user(23, Role.REVIEWER);
        Cookie otherCookie = TestLogins.cookieFor(other, users, tokenService);
        other.setEnabled(false);
        mvc.perform(get("/api/auth/me").cookie(otherCookie)).andExpect(status().isUnauthorized());

        when(users.findById(24L)).thenReturn(Optional.empty()); // deleted / unknown user id
        Cookie ghost = new Cookie(AuthCookies.AUTH_COOKIE, tokenService.issue(TestLogins.user(24, Role.ADMIN)));
        mvc.perform(get("/api/auth/me").cookie(ghost)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDeletesTheCookieAndNeedsCsrf() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());

        MvcResult result = mvc.perform(post("/api/auth/logout").with(csrf())).andExpect(status().isNoContent()).andReturn();
        assertThat(authSetCookie(result)).contains("Max-Age=0").contains("HttpOnly");
    }

    @Test
    void logoutEverywhereInvalidatesTokensAlreadyIssued() throws Exception {
        AppUser user = TestLogins.user(25, Role.VENDOR);
        Cookie cookie = TestLogins.cookieFor(user, users, tokenService);

        mvc.perform(post("/api/auth/logout-all").cookie(cookie).with(csrf())).andExpect(status().isNoContent());

        assertThat(user.getTokenVersion()).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ route rules

    @Test
    void adminUserApiIsAdminOnlyAndWritesNeedCsrf() throws Exception {
        Cookie vendor = TestLogins.cookieFor(TestLogins.user(30, Role.VENDOR), users, tokenService);
        Cookie reviewer = TestLogins.cookieFor(TestLogins.user(31, Role.REVIEWER), users, tokenService);
        Cookie admin = TestLogins.cookieFor(TestLogins.user(32, Role.ADMIN), users, tokenService);
        String body = "{\"email\":\"new@example.test\",\"displayName\":\"New\",\"role\":\"VENDOR\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").cookie(vendor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").cookie(reviewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").cookie(admin)).andExpect(status().isOk());

        mvc.perform(post("/api/admin/users").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(put("/api/admin/users/30/role").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"REVIEWER\"}")).andExpect(status().isForbidden()); // no CSRF token

        when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/api/admin/users").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.test"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void anythingNotListedIsDenied() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(40, Role.ADMIN), users, tokenService);
        mvc.perform(get("/api/not-a-route")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/not-a-route").cookie(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/invoices/extract/other").cookie(admin).with(csrf())).andExpect(status().isForbidden());
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(csrf()).with(address());
    }

    private static RequestPostProcessor address() {
        String address = "10.20.0." + NEXT_ADDRESS.getAndIncrement();
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String authSetCookie(MvcResult result) {
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(AuthCookies.AUTH_COOKIE + "=")) {
                return header;
            }
        }
        throw new AssertionError("no " + AuthCookies.AUTH_COOKIE + " Set-Cookie header");
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
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
