package com.actify.invoice.security;

import com.actify.invoice.controller.HealthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The backend is API-only. Protects three things a frontend and the hosting platform rely on:
 * the retired UI paths are no longer public, the health check is public but says nothing beyond "UP",
 * and an anonymous client still receives its XSRF-TOKEN cookie from its first API call (needed before login).
 */
@WebMvcTest(controllers = HealthController.class)
@WithInvoiceWebSecurity
class ApiOnlySurfaceWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokenService;
    @MockBean private AppUserRepository users;
    @MockBean private InvoiceRepository invoiceRepository;

    @Test
    void healthIsPublicGetOnlyAndRevealsNothingButUp() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        // A stale or broken login cookie must never make the platform think the service is down.
        mvc.perform(get("/api/health").cookie(new Cookie(AuthCookies.AUTH_COOKIE, "garbage")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/health").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void theRetiredUiPathsAreNoLongerPublic() throws Exception {
        String[] retired = {"/", "/index.html", "/login.html", "/admin.html", "/vendor.html", "/reviewer.html",
                "/legacy-workspace.html", "/cloud-auth-redirect.html", "/css/app.css", "/js/session.js", "/favicon.ico"};
        for (String path : retired) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        mvc.perform(get("/login.html").cookie(admin)).andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousApiCallHandsOutTheReadableCsrfCookie() throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        MvcResult health = mvc.perform(get("/api/health")).andExpect(status().isOk()).andReturn();

        for (MvcResult result : new MvcResult[]{me, health}) {
            Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
            assertThat(xsrf).as("XSRF-TOKEN cookie on an anonymous call").isNotNull();
            assertThat(xsrf.getValue()).isNotEmpty();
            assertThat(xsrf.isHttpOnly()).as("the frontend must be able to read it").isFalse();
        }
    }
}
