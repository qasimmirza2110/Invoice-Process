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

class JwtTokenServiceTest {

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
}
