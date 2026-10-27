package com.actify.invoice.security;

import com.actify.invoice.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes 401 and 403 responses produced by the security filters (before any controller runs) in the
 * same ErrorResponse JSON shape as GlobalExceptionHandler, so API clients see one error format everywhere.
 */
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorWriter.class);
    /** Names used by CookieCsrfTokenRepository (SecurityConfig) - compared, never logged. */
    static final String CSRF_COOKIE = "XSRF-TOKEN";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401: no valid login cookie. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, "Unauthorized", "Please log in.");
    }

    /** 403: logged in but not allowed, or the CSRF token is missing/wrong. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            log.warn(csrfDiagnostics(request));
        }
        String message = ex instanceof CsrfException
                ? "Missing or invalid CSRF token. Reload the page and try again."
                : "You do not have permission to do this.";
        write(response, request, HttpStatus.FORBIDDEN, "Forbidden", message);
    }

    /**
     * Why a CSRF check failed, WITHOUT any token value: whether the X-XSRF-TOKEN header was sent, how many XSRF-TOKEN
     * cookies arrived, and whether the header equals the first / any of them. That separates "header missing",
     * "cookie missing", "stale or mismatched token" and "two cookies" (e.g. different paths).
     */
    static String csrfDiagnostics(HttpServletRequest request) {
        String header = request.getHeader(CSRF_HEADER);
        int cookies = 0;
        Boolean matchesFirst = null;
        boolean matchesAny = false;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (CSRF_COOKIE.equals(cookie.getName())) {
                    boolean same = header != null && header.equals(cookie.getValue());
                    if (cookies == 0) {
                        matchesFirst = same;
                    }
                    matchesAny |= same;
                    cookies++;
                }
            }
        }
        return "CSRF refused: " + request.getMethod() + " " + request.getRequestURI()
                + " | header present=" + (header != null && !header.isEmpty())
                + " | XSRF-TOKEN cookies=" + cookies
                + " | header matches first cookie=" + matchesFirst
                + " | header matches any cookie=" + matchesAny;
    }

    void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String error,
               String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(status.value(), error, message, request.getRequestURI()));
    }
}
