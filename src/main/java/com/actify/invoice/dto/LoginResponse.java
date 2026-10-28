package com.actify.invoice.dto;

import java.time.Instant;

/**
 * Answer of POST /api/auth/login. The client keeps accessToken in memory and sends it as
 * "Authorization: Bearer <accessToken>" on every other call. "user" has exactly the shape of GET /api/auth/me.
 * Never logged (it contains the token).
 */
public class LoginResponse {

    private final String accessToken;
    private final long expiresIn;
    private final Instant expiresAt;
    private final MeResponse user;

    public LoginResponse(String accessToken, long expiresInSeconds, Instant expiresAt, MeResponse user) {
        this.accessToken = accessToken;
        this.expiresIn = expiresInSeconds;
        this.expiresAt = expiresAt;
        this.user = user;
    }

    public String getAccessToken() { return accessToken; }
    /** Always "Bearer". */
    public String getTokenType() { return "Bearer"; }
    /** Seconds from now until the token stops working (RFC 6749 style). */
    public long getExpiresIn() { return expiresIn; }
    /** The same moment as an ISO-8601 UTC timestamp, e.g. "2026-10-28T10:30:00Z". */
    public Instant getExpiresAt() { return expiresAt; }
    public MeResponse getUser() { return user; }

    @Override
    public String toString() {
        return "LoginResponse{token hidden}";
    }
}
