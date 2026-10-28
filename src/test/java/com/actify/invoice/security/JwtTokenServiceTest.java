package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login token itself: which claims it carries, that expiry, foreign keys and bad secrets are refused, and that the
 * application identity (issuer, audience) is exactly the one documented for frontends.
 */
class JwtTokenServiceTest {

    /** These values are part of the frontend/deployment contract (docs/REACT_BACKEND_HANDOFF.md); change them knowingly. */
    @Test
    void theApplicationIdentityIsActifyInvoiceProcess() {
        assertThat(JwtTokenService.ISSUER).isEqualTo("actify-invoice-process");
        assertThat(JwtTokenService.AUDIENCE).isEqualTo("actify-invoice-process-web");
    }

    /** The expiry the login response reports is exactly the token's own "exp" claim. */
    @Test
    void theReportedExpiryMatchesTheTokensExpClaim() {
        JwtTokenService service = TestLogins.tokenService();
        JwtTokenService.IssuedToken issued = service.issueWithExpiry(TestLogins.user(43, Role.VENDOR));
        Jwt jwt = service.decode(issued.getValue());
        assertThat(jwt.getExpiresAt()).isEqualTo(issued.getExpiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(issued.toString()).doesNotContain(issued.getValue());
    }

    @Test
    void tokenCarriesOnlyIdentityClaimsNotTheRole() {
        JwtTokenService service = TestLogins.tokenService();
        AppUser user = TestLogins.user(42, Role.ADMIN);
        user.bumpTokenVersion();

        Jwt jwt = service.decode(service.issue(user));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(JwtTokenService.ISSUER);
        assertThat(jwt.getAudience()).containsExactly(JwtTokenService.AUDIENCE);
        assertThat(((Number) jwt.getClaims().get("tv")).intValue()).isEqualTo(1);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plusSeconds(29 * 60));
        assertThat(jwt.getClaims()).doesNotContainKeys("role", "email", "authorities");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtTokenService service = TestLogins.tokenService();
        String token = service.issue(TestLogins.user(1, Role.VENDOR), Instant.now().minusSeconds(3600));

        assertThatThrownBy(() -> service.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        JwtTokenService other = new JwtTokenService(Base64.getEncoder().encodeToString(new byte[40]), 30);
        String token = other.issue(TestLogins.user(1, Role.ADMIN));

        assertThatThrownBy(() -> TestLogins.tokenService().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void missingShortOrInvalidSecretStopsStartupWithoutEchoingIt() {
        String shortSecret = Base64.getEncoder().encodeToString("only-twenty-bytes!!!".getBytes());

        assertThatThrownBy(() -> new JwtTokenService("", 30)).hasMessageContaining("not set");
        assertThatThrownBy(() -> new JwtTokenService(shortSecret, 30))
                .hasMessageContaining("too short").hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> new JwtTokenService("%%%not-base64%%%", 30))
                .hasMessageContaining("not valid Base64").hasMessageNotContaining("%%%not-base64%%%");
    }

    @Test
    void localDemoMayUseARandomKeyForOneRunWhenNoSecretIsSet() {
        JwtTokenService first = new JwtTokenService("", 30, true);
        JwtTokenService second = new JwtTokenService("", 30, true);
        String token = first.issue(TestLogins.user(5, Role.REVIEWER));

        assertThat(first.decode(token).getSubject()).isEqualTo("5");
        // A new run gets a new key, so an old access token is no longer valid.
        assertThatThrownBy(() -> second.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void theRandomKeyNeverHidesAConfiguredButBrokenSecret() {
        assertThatThrownBy(() -> new JwtTokenService("%%%not-base64%%%", 30, true)).hasMessageContaining("not valid Base64");
        assertThatThrownBy(() -> new JwtTokenService("", 30, false)).hasMessageContaining("not set");
    }
}
