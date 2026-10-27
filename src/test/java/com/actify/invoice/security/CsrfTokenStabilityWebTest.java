package com.actify.invoice.security;

import com.actify.invoice.controller.AuthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.AppUserService;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import javax.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The CSRF token must stay the same during a logged-in session. If every authenticated request issued a new
 * XSRF-TOKEN cookie, a POST could read token N while a parallel response replaces the cookie with N+1 just before the
 * POST is sent - an intermittent "Missing or invalid CSRF token" (observed in the real-browser pgtest on 2026-10-23:
 * header present, one cookie, header != cookie).
 */
@WebMvcTest(controllers = AuthController.class)
@WithInvoiceWebSecurity
@Import({LoginService.class, LoginAttemptLimiter.class, AppUserService.class})
class CsrfTokenStabilityWebTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokenService;

    @MockBean
    private AppUserRepository users;

    @MockBean
    private InvoiceRepository invoiceRepository;

    @Test
    void anAuthenticatedRequestDoesNotReplaceTheExistingCsrfToken() throws Exception {
        Cookie login = TestLogins.cookieFor(TestLogins.user(50, Role.ADMIN), users, tokenService);
        MockHttpServletResponse response = mvc.perform(get("/api/auth/me")
                        .cookie(login, new Cookie("XSRF-TOKEN", "token-the-page-already-has")))
                .andReturn().getResponse();

        List<String> xsrfSetCookies = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("XSRF-TOKEN=")).collect(Collectors.toList());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(xsrfSetCookies).as("Set-Cookie headers for XSRF-TOKEN on an ordinary authenticated GET").isEmpty();
        assertThat(Arrays.stream(response.getCookies()).map(Cookie::getName)).doesNotContain("XSRF-TOKEN");
    }
}
