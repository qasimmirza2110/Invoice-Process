package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * Runs once per request: INVOICE_PROCESS_AUTH cookie -> verified JWT -> user row from the database -> "logged in".
 *
 *  1. No cookie (or a public endpoint such as POST /api/auth/login): do nothing; the URL rules decide (usually 401).
 *  2. Cookie present: JwtTokenService checks signature, expiry, issuer and audience.
 *  3. The user is re-read from app_users: it must exist, be enabled, and have the same token version as the
 *     token's "tv" claim. The ROLE comes from this row, never from the token or the browser.
 *  4. Anything wrong -> the cookie is deleted and the request gets a 401, before any controller runs.
 *
 * Why not Spring's oauth2ResourceServer() DSL? In Spring Security 5.7 it switches CSRF protection OFF for
 * every request where it finds a token. With a cookie the browser sends automatically, that would remove
 * CSRF protection exactly where it is needed. This filter only authenticates; CsrfFilter still checks
 * every POST/PUT/DELETE (see SecurityConfig and the CSRF tests).
 *
 * Not a @Component on purpose: Spring Boot would then also register it as a plain servlet filter.
 * SecurityConfig creates it and places it in the security filter chain.
 */
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokenService;
    private final AppUserRepository userRepository;
    private final AuthCookies authCookies;
    private final SecurityErrorWriter errorWriter;
    private final RequestMatcher publicRequests;

    public JwtCookieAuthenticationFilter(JwtTokenService tokenService, AppUserRepository userRepository,
                                         AuthCookies authCookies, SecurityErrorWriter errorWriter,
                                         RequestMatcher publicRequests) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.authCookies = authCookies;
        this.errorWriter = errorWriter;
        this.publicRequests = publicRequests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = authCookies.readToken(request);
        // Public pages ignore the cookie: an expired cookie must never block the login page itself.
        if (token == null || publicRequests.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication;
        try {
            authentication = authenticate(tokenService.decode(token));
        } catch (JwtException | BadCredentialsException e) {
            // Never log the token itself.
            SecurityContextHolder.clearContext();
            authCookies.clear(request, response);
            errorWriter.commence(request, response, new BadCredentialsException("Invalid session"));
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken authenticate(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Bad subject");
        }
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            throw new BadCredentialsException("Unknown or disabled user");
        }
        Object tokenVersion = jwt.getClaims().get(JwtTokenService.TOKEN_VERSION_CLAIM);
        if (!(tokenVersion instanceof Number) || ((Number) tokenVersion).intValue() != user.getTokenVersion()) {
            throw new BadCredentialsException("Token was revoked");
        }
        AuthenticatedUser principal = AuthenticatedUser.from(user);
        return new UsernamePasswordAuthenticationToken(principal, null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
