package com.actify.invoice.security;

import com.actify.invoice.controller.HealthController;
import com.actify.invoice.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The single place that decides who may call which URL. Rules are checked top to bottom; the LAST rule denies
 * everything that was not explicitly allowed ("default deny").
 *
 * Authentication: every protected request carries "Authorization: Bearer <accessToken>" (the token from
 * POST /api/auth/login). See JwtBearerAuthenticationFilter. No cookie, no server session.
 *
 * Status codes:
 *  - 401: no token, or the token is invalid/expired/revoked, or the account was disabled
 *  - 403: valid token, but the role may not use this URL
 *  - 404: (from InvoiceAccessPolicy) the invoice/document exists only for other users - we don't reveal it
 *
 * CSRF protection is switched off on purpose, and only because authentication is header-only: a CSRF attack works by
 * making a victim's browser send a request that the browser authenticates AUTOMATICALLY (a cookie). Browsers never
 * add an Authorization header by themselves, so a forged cross-site request arrives without a token and gets a 401.
 * If a cookie-based login is ever added again, CSRF protection must come back with it.
 *
 * This backend is API-only: it serves no HTML pages, scripts or styles. Any URL not listed below - including "/" -
 * is denied (401 without a token, 403 with one).
 *
 * CORS: closed by default (no Access-Control-* headers, so browsers refuse cross-origin calls). The frontend then
 * runs on the same origin or behind a dev proxy (docs/react-handoff/vite.config.example.ts). INVOICE_CORS_ALLOWED_ORIGINS
 * opens it for an exact, approved list of origins - never "*", never with credentials (cookies are not used).
 */
@Configuration
public class SecurityConfig {

    /**
     * Calls that work without a token. An Authorization header is ignored on these, so an expired token can never
     * block logging in again. The health check reveals nothing beyond "the process is up" (HealthController).
     */
    static final RequestMatcher PUBLIC_REQUESTS = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/auth/login", "POST"),
            new AntPathRequestMatcher(HealthController.PATH, "GET"));

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokenService,
                                                   AppUserRepository userRepository, ObjectMapper objectMapper,
                                                   @Value("${invoice.cors.allowed-origins:}") String allowedOrigins)
            throws Exception {
        SecurityErrorWriter errorWriter = new SecurityErrorWriter(objectMapper);
        JwtBearerAuthenticationFilter jwtFilter = new JwtBearerAuthenticationFilter(
                tokenService, userRepository, errorWriter, PUBLIC_REQUESTS);
        List<String> origins = parseAllowedOrigins(allowedOrigins);

        http
                // No server-side session and no cookie: every request is authenticated from its Bearer header.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Safe only because of the header-only authentication above (see class comment).
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                // The PDF endpoint needs the Bearer header, so a frontend fetches it and shows a blob: URL; no site
                // needs to frame our responses directly.
                .headers(h -> h.frameOptions().deny())
                .exceptionHandling(e -> e.authenticationEntryPoint(errorWriter).accessDeniedHandler(errorWriter))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        if (!origins.isEmpty()) {
            http.cors(c -> c.configurationSource(corsSource(origins)));
        }
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_REQUESTS).permitAll()
                .antMatchers("/error").permitAll()

                .antMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .antMatchers(HttpMethod.POST, "/api/auth/logout-all").authenticated()

                .antMatchers("/api/admin/**").hasRole("ADMIN")

                // Batch upload: vendors (and admins) only; ownership is checked per batch (404 otherwise).
                .antMatchers("/api/batches", "/api/batches/**").hasAnyRole("VENDOR", "ADMIN")
                .antMatchers(HttpMethod.GET, "/api/categories").authenticated()
                // Which cloud pickers exist (public browser values only, never a secret or token).
                .antMatchers(HttpMethod.GET, "/api/cloud-sources").hasAnyRole("VENDOR", "ADMIN")

                // Legacy single-file extraction: ADMIN only.
                .antMatchers(HttpMethod.POST, "/api/invoices/extract").hasRole("ADMIN")
                // Review workflow: the reviewer submits, the admin decides.
                .antMatchers(HttpMethod.POST, "/api/invoices/*/submit").hasRole("REVIEWER")
                .antMatchers(HttpMethod.POST, "/api/invoices/*/approve", "/api/invoices/*/reject",
                        "/api/invoices/*/send-back", "/api/invoices/*/reopen", "/api/invoices/*/requeue").hasRole("ADMIN")
                // Corrections: assigned reviewer (workflow) or admin (legacy) - InvoiceAccessPolicy.requireCanCorrect.
                .antMatchers(HttpMethod.PUT, "/api/invoices/*").hasAnyRole("ADMIN", "REVIEWER")
                // Reads need a login; WHICH invoices a user sees is decided per object (404 otherwise).
                .antMatchers(HttpMethod.GET, "/api/invoices", "/api/invoices/**", "/api/documents/**").authenticated()
                .antMatchers(HttpMethod.GET, "/api/dashboard/**").authenticated()

                .anyRequest().denyAll());
        return http.build();
    }

    /**
     * "https://app.example.com, http://localhost:5173" -> exact origins. Refuses the start on anything that is not a
     * plain scheme://host[:port] (no "*", no path, no trailing slash), so a typo can never open the API wider than
     * intended. Empty -> no CORS at all.
     */
    static List<String> parseAllowedOrigins(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> origins = new ArrayList<>();
        for (String raw : value.split(",")) {
            String origin = raw.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (!isExactOrigin(origin)) {
                throw new IllegalStateException("INVOICE_CORS_ALLOWED_ORIGINS contains an invalid entry: each one must "
                        + "be an exact origin such as https://app.example.com (no '*', path or trailing '/')");
            }
            origins.add(origin);
        }
        return origins;
    }

    private static boolean isExactOrigin(String origin) {
        if (origin.contains("*")) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            boolean httpScheme = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
            return httpScheme && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Spring Security's CorsFilter answers a valid preflight (OPTIONS) itself, before the URL rules run, and refuses
     * one from an origin that is not listed.
     */
    static CorsConfigurationSource corsSource(List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(Arrays.asList("GET", "POST", "PUT"));
        cors.setAllowedHeaders(Arrays.asList(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        cors.setExposedHeaders(Collections.singletonList(HttpHeaders.CONTENT_DISPOSITION));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /**
     * Spring Security's firewall rejects suspicious URLs (encoded "..", backslashes...). Without this bean the
     * rejection surfaces as a 500; with it the client gets a plain 400, as before Spring Security was added.
     */
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler(HttpStatus.BAD_REQUEST.value());
    }

    /** BCrypt with the default cost 10 (OWASP minimum). Passwords over 72 bytes are rejected before this. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
