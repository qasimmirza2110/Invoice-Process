package com.actify.invoice.security;

import com.actify.invoice.config.RenderFreeDemoGuard;
import com.actify.invoice.controller.HealthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.servlet.http.Cookie;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The free demo with the REAL security configuration: every answer - also 401/403 from the security filters - is
 * marked as demo, the health body is unchanged, CSRF and authentication behave exactly as in production, and the
 * facts a browser frontend depends on hold: no CORS headers for another origin, host-only cookies (no Domain).
 */
@WebMvcTest(controllers = HealthController.class)
@WithInvoiceWebSecurity
@ActiveProfiles(RenderFreeDemoGuard.PROFILE)
class FreeDemoSurfaceWebTest {

    private static final String DEMO = RenderFreeDemoGuard.HEADER_NAME;

    @Autowired private MockMvc mvc;
    @MockBean private AppUserRepository users;
    @MockBean private InvoiceRepository invoiceRepository;

    @Test
    void healthKeepsItsExactBodyAndCarriesTheDemoMarker() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true))
                .andExpect(header().string(DEMO, RenderFreeDemoGuard.HEADER_VALUE));
    }

    @Test
    void securityAnswersAreUnchangedAndMarkedToo() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string(DEMO, RenderFreeDemoGuard.HEADER_VALUE));
        mvc.perform(get("/")).andExpect(status().isUnauthorized())
                .andExpect(header().string(DEMO, RenderFreeDemoGuard.HEADER_VALUE));
        // No CSRF header -> refused before anything else (403), exactly as in production.
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"a@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(DEMO, RenderFreeDemoGuard.HEADER_VALUE));
    }

    @Test
    void anotherOriginGetsNoCorsPermission() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(get("/api/health").header("Origin", "https://frontend.example"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** Host-only cookies are what make a same-origin dev proxy work: the browser stores them for the proxy's host. */
    @Test
    void cookiesAreHostOnlyWithPathSlash() throws Exception {
        MvcResult health = mvc.perform(get("/api/health")).andReturn();
        Cookie xsrf = health.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.getDomain()).isNull();
        assertThat(xsrf.getPath()).isEqualTo("/");
        assertThat(xsrf.isHttpOnly()).isFalse();

        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthCookies(true).setToken(new MockHttpServletRequest(), response, "token", Duration.ofMinutes(30));
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith(AuthCookies.AUTH_COOKIE + "=")
                .contains("Path=/", "HttpOnly", "Secure", "SameSite=Strict")
                .doesNotContainIgnoringCase("Domain=");
    }
}
