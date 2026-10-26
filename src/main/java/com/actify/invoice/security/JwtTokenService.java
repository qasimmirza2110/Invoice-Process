package com.actify.invoice.security;

import com.actify.invoice.user.AppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Creates and checks the login token (a JWT). We are both the issuer and the only consumer, so a shared
 * secret (HMAC-SHA256, "HS256") is enough. All cryptography is done by Spring Security + Nimbus,
 * never by hand. JwtCompatibilitySpikeTest proves these library classes behave as expected.
 *
 * Token contents: iss, aud, sub (user id), tv (token version), iat, exp, jti. The role is NOT in the token:
 * it is read from app_users on every request, so a role change takes effect immediately.
 *
 * Issuer and audience name this application ("Actify Invoice Process"). Encoding and decoding share the same two
 * constants, so a token is only accepted when both values match exactly. Tokens issued under the earlier project
 * identity (issuer "task8-invoice", audience "task8-web") fail this check: after the rename every user logs in once
 * more. That is intended - an old token never keeps working just because it is still signed with the same key.
 */
@Component
public class JwtTokenService {

    public static final String ISSUER = "actify-invoice-process";
    public static final String AUDIENCE = "actify-invoice-process-web";
    public static final String TOKEN_VERSION_CLAIM = "tv";
    static final int MIN_SECRET_BYTES = 32;
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration lifetime;

    public JwtTokenService(String base64Secret, long lifetimeMinutes) {
        this(base64Secret, lifetimeMinutes, false);
    }

    /**
     * @param allowEphemeralSecret true only in the localdemo profile (invoice.jwt.allow-ephemeral-secret): when no
     *                             INVOICE_JWT_SECRET is configured, a random key is made for this run only, so a plain
     *                             "Run As > Java Application" in Eclipse works. Logins then end when the app stops.
     */
    @Autowired
    public JwtTokenService(@Value("${invoice.jwt.secret:}") String base64Secret,
                           @Value("${invoice.jwt.ttl-minutes:30}") long lifetimeMinutes,
                           @Value("${invoice.jwt.allow-ephemeral-secret:false}") boolean allowEphemeralSecret) {
        byte[] secret;
        if (!StringUtils.hasText(base64Secret) && allowEphemeralSecret) {
            secret = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            log.warn("INVOICE_JWT_SECRET is not set: using a random signing key for this run only (local demo). "
                    + "Everyone has to log in again after a restart.");
        } else {
            secret = decodeSecret(base64Secret);
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(AUDIENCE));
        // The default validators check exp/nbf (60 s clock skew); we add issuer and audience.
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        this.lifetime = Duration.ofMinutes(lifetimeMinutes);
    }

    /** The secret is never printed - not even in these messages - so a startup log can be shared safely. */
    private static byte[] decodeSecret(String base64Secret) {
        if (!StringUtils.hasText(base64Secret)) {
            throw new IllegalStateException("INVOICE_JWT_SECRET is not set. Put a Base64 value of at least "
                    + MIN_SECRET_BYTES + " random bytes in the launch configuration.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("INVOICE_JWT_SECRET is not valid Base64.");
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("INVOICE_JWT_SECRET is too short: it must decode to at least "
                    + MIN_SECRET_BYTES + " bytes.");
        }
        return bytes;
    }

    /**
     * Signs a new login token for this user, valid for {@link #getLifetime()} from now. The token carries the user's
     * current token version ("tv"), so raising that version in the database (role change, disable, "log out
     * everywhere") makes every earlier token useless on its next request.
     */
    public String issue(AppUser user) {
        return issue(user, Instant.now());
    }

    // Package-private so tests can create an already-expired token.
    String issue(AppUser user, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(Collections.singletonList(AUDIENCE))
                .subject(String.valueOf(user.getId()))
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Checks signature (HS256 only), expiry, issuer and audience. Throws JwtException when anything is wrong. */
    public Jwt decode(String token) throws JwtException {
        return decoder.decode(token);
    }

    public Duration getLifetime() {
        return lifetime;
    }
}
