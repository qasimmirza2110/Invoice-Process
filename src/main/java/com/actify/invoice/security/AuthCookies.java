package com.actify.invoice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

/**
 * The login cookie "TASK8_AUTH".
 *  - HttpOnly: JavaScript can never read the token, so an XSS bug cannot steal it.
 *  - SameSite=Strict: the browser does not send it on requests started from another site.
 *  - Secure: always on HTTPS requests; on the plain-HTTP local demo only when INVOICE_COOKIE_SECURE=true.
 */
@Component
public class AuthCookies {

    public static final String AUTH_COOKIE = "TASK8_AUTH";

    private final boolean forceSecure;

    public AuthCookies(@Value("${invoice.auth.cookie-secure:false}") boolean forceSecure) {
        this.forceSecure = forceSecure;
    }

    public void setToken(HttpServletRequest request, HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, token, maxAge).toString());
    }

    /** Max-Age=0 tells the browser to delete the cookie. */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, "", Duration.ZERO).toString());
    }

    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AUTH_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(AUTH_COOKIE, value)
                .httpOnly(true)
                .secure(forceSecure || request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
