package com.actify.invoice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

/** The CSRF-refusal log line separates the possible causes and never contains a token value. */
class CsrfDiagnosticsTest {

    private static MockHttpServletRequest post(String header, String... cookieValues) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/invoices/7/approve");
        if (header != null) {
            r.addHeader("X-XSRF-TOKEN", header);
        }
        Cookie[] cookies = new Cookie[cookieValues.length + 1];
        cookies[0] = new Cookie(AuthCookies.AUTH_COOKIE, "auth-cookie-value-never-logged");
        for (int i = 0; i < cookieValues.length; i++) {
            cookies[i + 1] = new Cookie("XSRF-TOKEN", cookieValues[i]);
        }
        r.setCookies(cookies);
        return r;
    }

    @Test
    void headerMissing() {
        assertThat(SecurityErrorWriter.csrfDiagnostics(post(null, "tok-AAAA")))
                .isEqualTo("CSRF refused: POST /api/invoices/7/approve | header present=false | XSRF-TOKEN cookies=1"
                        + " | header matches first cookie=false | header matches any cookie=false");
    }

    @Test
    void cookieMissing() {
        assertThat(SecurityErrorWriter.csrfDiagnostics(post("tok-AAAA")))
                .contains("header present=true").contains("XSRF-TOKEN cookies=0").contains("matches first cookie=null");
    }

    @Test
    void staleTokenAndTwoCookiesAreDistinguishable() {
        assertThat(SecurityErrorWriter.csrfDiagnostics(post("tok-OLD", "tok-NEW")))
                .contains("cookies=1").contains("matches first cookie=false").contains("matches any cookie=false");
        assertThat(SecurityErrorWriter.csrfDiagnostics(post("tok-BBBB", "tok-AAAA", "tok-BBBB")))
                .contains("cookies=2").contains("matches first cookie=false").contains("matches any cookie=true");
    }

    @Test
    void noTokenOrCookieValueIsEverLogged() {
        String line = SecurityErrorWriter.csrfDiagnostics(post("tok-BBBB", "tok-AAAA", "tok-BBBB"));
        assertThat(line).doesNotContain("tok-").doesNotContain("auth-cookie-value");
    }
}
