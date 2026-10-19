package com.actify.invoice.security;

import com.actify.invoice.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The single place that decides who may call which URL. Rules are checked top to bottom; the LAST rule denies
 * everything that was not explicitly allowed ("default deny").
 *
 * Status codes:
 *  - 401: not logged in (or the login cookie is invalid/expired/revoked)
 *  - 403: logged in, but the role may not use this URL - or the CSRF token is missing
 *  - 404: (from InvoiceAccessPolicy) the invoice/document exists only for other users - we don't reveal it
 *
 * CSRF: the login cookie is sent by the browser automatically, so every POST/PUT/DELETE must also carry the
 * X-XSRF-TOKEN header, copied by our JavaScript from the readable XSRF-TOKEN cookie. Another website cannot
 * read that cookie, so it cannot forge the header. CSRF is never disabled.
 */
@Configuration
public class SecurityConfig {

    /** Pages and calls that work without logging in. The login cookie is ignored on these. */
    static final RequestMatcher PUBLIC_REQUESTS = new OrRequestMatcher(
            new AntPathRequestMatcher("/", "GET"),
            new AntPathRequestMatcher("/index.html", "GET"),
            new AntPathRequestMatcher("/login.html", "GET"),
            // Role pages (vendor/reviewer/admin.html) hold no data: each one loads its data from the protected API
            // and sends a non-matching role to its own home page. The API decides what anyone may see.
            new AntPathRequestMatcher("/*.html", "GET"),
            new AntPathRequestMatcher("/css/**", "GET"),
            new AntPathRequestMatcher("/js/**", "GET"),
            new AntPathRequestMatcher("/favicon.ico", "GET"),
            new AntPathRequestMatcher("/api/auth/login", "POST"),
            new AntPathRequestMatcher("/api/auth/logout", "POST"));

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokenService,
                                                   AppUserRepository userRepository, AuthCookies authCookies,
                                                   ObjectMapper objectMapper) throws Exception {
        SecurityErrorWriter errorWriter = new SecurityErrorWriter(objectMapper);
        JwtCookieAuthenticationFilter jwtFilter = new JwtCookieAuthenticationFilter(
                tokenService, userRepository, authCookies, errorWriter, PUBLIC_REQUESTS);

        http
                // No server-side session: every request is authenticated from its cookie.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // XSRF-TOKEN cookie (readable by our JS) + X-XSRF-TOKEN header on every unsafe request.
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                // We have our own JSON login endpoint; switch off the HTML/basic login and logout pages.
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                // The review screen shows the stored PDF in a same-origin frame.
                .headers(h -> h.frameOptions().sameOrigin())
                .exceptionHandling(e -> e.authenticationEntryPoint(errorWriter).accessDeniedHandler(errorWriter))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_REQUESTS).permitAll()
                        .antMatchers("/error").permitAll()

                        .antMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .antMatchers(HttpMethod.POST, "/api/auth/logout-all").authenticated()

                        .antMatchers("/api/admin/**").hasRole("ADMIN")

                        // Batch upload: vendors (and admins) only; ownership is checked per batch (404 otherwise).
                        .antMatchers("/api/batches", "/api/batches/**").hasAnyRole("VENDOR", "ADMIN")
                        .antMatchers(HttpMethod.GET, "/api/categories").authenticated()

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
