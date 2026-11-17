package com.daktela.connector.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Access and refresh token of a session started by logging in with a username and password.
 * <p>
 * Both are JWTs. Their expiry is read from the {@code exp} claim without verifying the signature,
 * which only the Daktela server can do. Instances are immutable; {@link #toString()} never
 * includes the tokens.
 */
public final class TokenSet {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String accessToken;
    private final String refreshToken;
    private final String owner;
    private final Instant accessTokenExpiresAt;
    private final Instant refreshTokenExpiresAt;

    private TokenSet(String accessToken, String refreshToken, String owner) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken is required");
        this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken is required");
        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IllegalArgumentException("accessToken and refreshToken must not be empty");
        }
        this.owner = owner;
        this.accessTokenExpiresAt = expiry(accessToken);
        this.refreshTokenExpiresAt = expiry(refreshToken);
    }

    /**
     * Creates a token set, reading the expiry of each token from its {@code exp} claim. Use it to
     * restore tokens that a {@link TokenStore} saved.
     *
     * @param accessToken  the access token returned by the API
     * @param refreshToken the refresh token returned by the API
     * @param owner        the session the tokens belong to, as returned by {@link #getOwner()}, or null
     * @return the token set
     * @throws IllegalArgumentException if a token is empty
     */
    public static TokenSet of(String accessToken, String refreshToken, String owner) {
        return new TokenSet(accessToken, refreshToken, owner);
    }

    /**
     * Returns the access token.
     *
     * @return the access token
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Returns the refresh token. It is a long-lived credential: store it as securely as a password.
     *
     * @return the refresh token
     */
    public String getRefreshToken() {
        return refreshToken;
    }

    /**
     * Returns the session the tokens were issued for, as {@code username@instance URL}. The connector
     * ignores stored tokens of another session. This is not authentication: see {@link TokenStore}.
     *
     * @return the owner, or null when unknown
     */
    public String getOwner() {
        return owner;
    }

    /**
     * Returns when the access token expires.
     *
     * @return the expiry, or null when the token has no readable {@code exp} claim
     */
    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    /**
     * Returns when the refresh token expires.
     *
     * @return the expiry, or null when the token has no readable {@code exp} claim
     */
    public Instant getRefreshTokenExpiresAt() {
        return refreshTokenExpiresAt;
    }

    @Override
    public String toString() {
        return "TokenSet{owner=" + owner
                + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + '}';
    }

    private static Instant expiry(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode exp = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).get("exp");
            return exp != null && exp.canConvertToLong() && exp.isIntegralNumber()
                    ? Instant.ofEpochSecond(exp.longValue())
                    : null;
        } catch (IllegalArgumentException | IOException | DateTimeException e) {
            // Not a JWT, or an exp claim outside the range of Instant: the expiry is unknown.
            return null;
        }
    }
}
