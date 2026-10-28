package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * Builds real access tokens for MockMvc tests: a user that the (mocked) repository will return, and an
 * "Authorization: Bearer ..." header signed by the real JwtTokenService - so the real JwtBearerAuthenticationFilter runs.
 */
public final class TestLogins {

    /** Base64 of a fixed, public, test-only text. Not a secret; never used outside tests. */
    public static final String TEST_SECRET = "d2VibXZjLXRlc3Qtb25seS1rZXktbm90LWEtcmVhbC1zZWNyZXQ=";

    private TestLogins() {
    }

    public static AppUser user(long id, Role role) {
        AppUser user = new AppUser(role.name().toLowerCase() + id + "@example.test", role.name() + " " + id,
                "$2a$10$notarealhashnotarealhashnotarealhashnotarealhashnotare", role);
        user.setIdForTest(id);
        return user;
    }

    /** Makes the mocked repository know the user and returns a valid access token for it. */
    public static String tokenFor(AppUser user, AppUserRepository repository, JwtTokenService tokens) {
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        return tokens.issue(user);
    }

    /** Same as tokenFor, already wrapped as the request's Authorization header. */
    public static RequestPostProcessor bearerFor(AppUser user, AppUserRepository repository, JwtTokenService tokens) {
        return bearer(tokenFor(user, repository, tokens));
    }

    /** "Authorization: Bearer <token>" - exactly what the React client sends. */
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    public static JwtTokenService tokenService() {
        return new JwtTokenService(TEST_SECRET, 30);
    }
}
