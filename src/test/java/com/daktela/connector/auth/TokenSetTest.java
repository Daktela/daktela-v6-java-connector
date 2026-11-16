package com.daktela.connector.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TokenSetTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    private static String jwt(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    @Test
    void expiryIsReadFromTheExpClaim() {
        long exp = NOW.plus(Duration.ofHours(1)).getEpochSecond();
        TokenSet tokens = TokenSet.of(jwt("{\"exp\":" + exp + "}"), jwt("{\"exp\":" + (exp + 3600) + "}"), "u@i");

        assertEquals(Instant.ofEpochSecond(exp), tokens.getAccessTokenExpiresAt());
        assertEquals(Instant.ofEpochSecond(exp + 3600), tokens.getRefreshTokenExpiresAt());
        assertFalse(tokens.accessTokenExpiresWithin(Duration.ofMinutes(59), NOW));
        assertTrue(tokens.accessTokenExpiresWithin(Duration.ofMinutes(60), NOW));
        assertFalse(tokens.isRefreshTokenExpired(NOW));
        assertTrue(tokens.isRefreshTokenExpired(Instant.ofEpochSecond(exp + 3600)));
    }

    @Test
    void tokensWithoutAReadableExpiryNeverExpire() {
        TokenSet tokens = TokenSet.of("static-token", jwt("{\"exp\":\"soon\"}"), null);

        assertNull(tokens.getAccessTokenExpiresAt());
        assertNull(tokens.getRefreshTokenExpiresAt());
        assertFalse(tokens.accessTokenExpiresWithin(Duration.ofDays(365), NOW));
        assertFalse(tokens.isRefreshTokenExpired(NOW));
        assertNull(TokenSet.of("a.!!!.c", "b", null).getAccessTokenExpiresAt());
    }

    @Test
    void emptyTokensAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenSet.of("", "refresh", null));
        assertThrows(NullPointerException.class, () -> TokenSet.of("access", null, null));
    }

    @Test
    void toStringHidesTheTokens() {
        TokenSet tokens = TokenSet.of("access-secret", "refresh-secret", "jirka@https://my.daktela.com");

        assertFalse(tokens.toString().contains("secret"), tokens.toString());
        assertTrue(tokens.toString().contains("jirka@https://my.daktela.com"), tokens.toString());
    }
}
