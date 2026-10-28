package com.actify.invoice.security;

import com.actify.invoice.controller.AuthController;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.AppUserService;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CORS is closed unless an exact origin list is configured, and never allows credentials or "*". */
class CorsWebTest {

    @Test
    void onlyExactOriginsAreAccepted() {
        assertThat(SecurityConfig.parseAllowedOrigins("")).isEmpty();
        assertThat(SecurityConfig.parseAllowedOrigins(" https://invoices.example.com , http://localhost:5173 "))
                .containsExactly("https://invoices.example.com", "http://localhost:5173");
        for (String bad : new String[]{"*", "https://*.example.com", "https://invoices.example.com/",
                "https://invoices.example.com/app", "invoices.example.com", "ftp://invoices.example.com",
                "https://user@invoices.example.com"}) {
            assertThatThrownBy(() -> SecurityConfig.parseAllowedOrigins(bad)).as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @WebMvcTest(controllers = AuthController.class)
    @WithInvoiceWebSecurity
    @Import({LoginService.class, LoginAttemptLimiter.class, AppUserService.class})
    class ByDefault {
        @Autowired private MockMvc mvc;
        @MockBean private AppUserRepository users;
        @MockBean private InvoiceRepository invoiceRepository;

        @Test
        void noCorsHeadersAreSent() throws Exception {
            String allow = mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "https://evil.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andReturn().getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
            assertThat(allow).isNull();
        }
    }

    @Nested
    @WebMvcTest(controllers = AuthController.class)
    @WithInvoiceWebSecurity
    @Import({LoginService.class, LoginAttemptLimiter.class, AppUserService.class})
    @TestPropertySource(properties = "invoice.cors.allowed-origins=https://invoices.example.com")
    class WhenConfigured {
        @Autowired private MockMvc mvc;
        @Autowired private JwtTokenService tokenService;
        @MockBean private AppUserRepository users;
        @MockBean private InvoiceRepository invoiceRepository;

        @Test
        void theApprovedOriginGetsAPreflightThatAllowsTheAuthorizationHeader() throws Exception {
            mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "https://invoices.example.com")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://invoices.example.com"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

            mvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "https://invoices.example.com")
                            .with(TestLogins.bearerFor(TestLogins.user(5, Role.VENDOR), users, tokenService)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://invoices.example.com"));
        }

        @Test
        void anyOtherOriginIsRefused() throws Exception {
            mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "https://evil.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
