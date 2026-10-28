package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import org.springframework.http.HttpHeaders;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs once per request: "Authorization: Bearer <token>" header -> verified JWT -> user row from the database ->
 * "logged in".
 *
 *  1. No Authorization header, another scheme (e.g. "Basic"), or a public endpoint such as POST /api/auth/login:
 *     do nothing; the URL rules decide (usually 401).
 *  2. "Bearer ..." present: JwtTokenService checks signature (HS256 only), expiry, issuer and audience.
 *  3. The user is re-read from app_users: it must exist, be enabled, and have the same token version as the
 *     token's "tv" claim. The ROLE comes from this row, never from the token or the client.
 *  4. Anything wrong -> 401, before any controller runs.
 *
 * The header is the ONLY place a token is read from: never a cookie, a URL/query parameter or a request body. Because
 * a browser never attaches this header on its own, another website cannot make a logged-in user's browser send an
 * authenticated request - which is why CSRF protection is not needed (SecurityConfig).
 *
 * Not a @Component on purpose: Spring Boot would then also register it as a plain servlet filter.
 * SecurityConfig creates it and places it in the security filter chain.
 */
public class JwtBearerAuthenticationFilter extends OncePerRequestFilter {

    /** RFC 6750 "Bearer" is case-insensitive; the token itself is the JWT's three base64url parts. */
    private static final Pattern BEARER = Pattern.compile("^Bearer +([A-Za-z0-9_.~+/-]+=*) *$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_BEARER = Pattern.compile("^Bearer(\\s.*)?$", Pattern.CASE_INSENSITIVE);

    private final JwtTokenService tokenService;
    private final AppUserRepository userRepository;
    private final SecurityErrorWriter errorWriter;
    private final RequestMatcher publicRequests;

    public JwtBearerAuthenticationFilter(JwtTokenService tokenService, AppUserRepository userRepository,
                                         SecurityErrorWriter errorWriter, RequestMatcher publicRequests) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.errorWriter = errorWriter;
        this.publicRequests = publicRequests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // Public endpoints ignore the header: an expired token must never block logging in again.
        if (header == null || publicRequests.matches(request) || !ANY_BEARER.matcher(header.trim()).matches()) {
            chain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication;
        try {
            Matcher matcher = BEARER.matcher(header.trim());
            if (!matcher.matches()) {
                throw new BadCredentialsException("Malformed bearer token");
            }
            authentication = authenticate(tokenService.decode(matcher.group(1)));
        } catch (JwtException | BadCredentialsException e) {
            // Never log the token itself; the response does not say which check failed.
            SecurityContextHolder.clearContext();
            errorWriter.commence(request, response, new BadCredentialsException("Invalid token"));
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
