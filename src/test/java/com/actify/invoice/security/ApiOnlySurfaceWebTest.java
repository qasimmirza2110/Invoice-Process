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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The backend is API-only. Protects what a frontend and the hosting platform rely on: the retired UI paths
 * (including "/") are not public - a 401 there is expected, not an outage - the health check is public but says
 * nothing beyond "UP", and no cookie of any kind is handed out.
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
        // A stale or broken token must never make the platform think the service is down.
        mvc.perform(get("/api/health").with(TestLogins.bearer("garbage")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRetiredUiPathsAreNoLongerPublic() throws Exception {
        String[] retired = {"/", "/index.html", "/login.html", "/admin.html", "/vendor.html", "/reviewer.html",
                "/legacy-workspace.html", "/cloud-auth-redirect.html", "/css/app.css", "/js/session.js", "/favicon.ico"};
        for (String path : retired) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        RequestPostProcessor admin = TestLogins.bearerFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        mvc.perform(get("/login.html").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void noCookieIsEverSetAndNoSessionIsCreated() throws Exception {
        RequestPostProcessor admin = TestLogins.bearerFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        MvcResult[] results = {
                mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn(),
                mvc.perform(get("/api/health")).andExpect(status().isOk()).andReturn(),
                // Authenticated (AuthController itself is not part of this slice, so the request ends in a 404).
                mvc.perform(get("/api/auth/me").with(admin)).andExpect(status().isNotFound()).andReturn()};
        for (MvcResult result : results) {
            assertThat(result.getResponse().getCookies()).as("cookies").isEmpty();
            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(result.getRequest().getSession(false)).as("server session").isNull();
        }
    }
}
