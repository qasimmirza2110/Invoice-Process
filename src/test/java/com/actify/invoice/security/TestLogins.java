package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;

import javax.servlet.http.Cookie;
import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * Builds real login cookies for MockMvc tests: a user that the (mocked) repository will return, and a
 * INVOICE_PROCESS_AUTH cookie signed by the real JwtTokenService - so the real JwtCookieAuthenticationFilter runs.
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

    /** Makes the mocked repository know the user and returns a valid login cookie for it. */
    public static Cookie cookieFor(AppUser user, AppUserRepository repository, JwtTokenService tokens) {
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        return new Cookie(AuthCookies.AUTH_COOKIE, tokens.issue(user));
    }

    public static JwtTokenService tokenService() {
        return new JwtTokenService(TEST_SECRET, 30);
    }
}
