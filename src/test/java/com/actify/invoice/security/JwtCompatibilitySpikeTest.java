package com.actify.invoice.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64URL;
import org.junit.jupiter.api.Test;
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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that Spring Security 5.7.11's JWT classes work with the nimbus-jose-jwt 9.37.4 override
 * (pom.xml) for exactly the way this app uses JWTs: HS256 only, our own issuer and audience.
 * If a future dependency change breaks any of these, this test fails before any login code does.
 */
class JwtCompatibilitySpikeTest {

    private static final String ISSUER = "task8-invoice";
    private static final String AUDIENCE = "task8-web";

    private final SecretKey key = randomKey();
    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    private final NimbusJwtDecoder decoder = decoderFor(key);

    @Test
    void hs256RoundTrip() {
        String token = encode(Instant.now(), Instant.now().plusSeconds(1800), ISSUER, AUDIENCE);

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getClaimAsString("tv")).isEqualTo("0");
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        NimbusJwtEncoder otherEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(randomKey()));
        String token = otherEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(Instant.now(), Instant.now().plusSeconds(1800), ISSUER, AUDIENCE))).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedPayload() {
        String token = encode(Instant.now(), Instant.now().plusSeconds(1800), ISSUER, AUDIENCE);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64URL.encode(
                "{\"sub\":\"1\",\"iss\":\"task8-invoice\",\"aud\":\"task8-web\",\"tv\":0}").toString();
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        // Expired 10 minutes ago - well outside the default 60 s clock skew.
        String token = encode(Instant.now().minusSeconds(3600), Instant.now().minusSeconds(600), ISSUER, AUDIENCE);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAlgNone() {
        String header = Base64URL.encode("{\"alg\":\"none\",\"typ\":\"JWT\"}").toString();
        String payload = Base64URL.encode("{\"sub\":\"42\",\"iss\":\"task8-invoice\",\"aud\":\"task8-web\",\"tv\":0,\"exp\":"
                + Instant.now().plusSeconds(600).getEpochSecond() + "}").toString();

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHs512EvenWithTheRightKey() throws Exception {
        // HS512 needs a 64-byte key; the decoder must still refuse it because only HS256 is accepted.
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        SecretKey key512 = new SecretKeySpec(bytes, "HmacSHA512");
        String token = signRaw(new JWSHeader(JWSAlgorithm.HS512), validPayloadJson(), bytes);

        assertThatThrownBy(() -> decoderFor(key512).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongIssuer() {
        String token = encode(Instant.now(), Instant.now().plusSeconds(1800), "someone-else", AUDIENCE);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudience() {
        String token = encode(Instant.now(), Instant.now().plusSeconds(1800), ISSUER, "another-app");

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    /**
     * CVE-2025-53864: nimbus-jose-jwt before 9.37.4 recursed without limit on deeply nested claim JSON.
     * The token below is correctly signed (worst case: the parser really reaches the claims), and has
     * 10,000 nested objects. 9.37.4 must refuse it with a normal exception, not a StackOverflowError.
     */
    @Test
    void deeplyNestedClaimsAreRejectedWithoutStackOverflow() throws Exception {
        int depth = 10_000;
        StringBuilder json = new StringBuilder(validPayloadJson().replaceAll("}$", ",\"x\":"));
        for (int i = 0; i < depth; i++) {
            json.append("{\"a\":");
        }
        json.append("1");
        for (int i = 0; i < depth; i++) {
            json.append('}');
        }
        json.append('}');
        String token = signRaw(new JWSHeader(JWSAlgorithm.HS256), json.toString(), key.getEncoded());

        Throwable thrown = null;
        try {
            decoder.decode(token);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).as("nested token must be rejected").isNotNull();
        assertThat(thrown).isInstanceOf(JwtException.class).isNotInstanceOf(StackOverflowError.class);
    }

    // ---------------------------------------------------------------- helpers

    private String encode(Instant issuedAt, Instant expiresAt, String issuer, String audience) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(issuedAt, expiresAt, issuer, audience))).getTokenValue();
    }

    private static JwtClaimsSet claims(Instant issuedAt, Instant expiresAt, String issuer, String audience) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(Collections.singletonList(audience))
                .subject("42")
                .claim("tv", 0)
                .id("jti-1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }

    private static String validPayloadJson() {
        long now = Instant.now().getEpochSecond();
        return "{\"sub\":\"42\",\"iss\":\"task8-invoice\",\"aud\":\"task8-web\",\"tv\":0,\"iat\":" + now
                + ",\"exp\":" + (now + 600) + "}";
    }

    /** Signs an arbitrary header + payload with the Nimbus library signer (no hand-written crypto). */
    private static String signRaw(JWSHeader header, String payloadJson, byte[] secret) throws Exception {
        String signingInput = header.toBase64URL() + "." + Base64URL.encode(payloadJson.getBytes(StandardCharsets.UTF_8));
        Base64URL signature = new MACSigner(secret).sign(header, signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + signature;
    }

    private static NimbusJwtDecoder decoderFor(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        return decoder;
    }

    private static SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
