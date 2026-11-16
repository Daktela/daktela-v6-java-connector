package com.daktela.connector;

import com.daktela.connector.auth.TokenSet;
import com.daktela.connector.auth.TokenStore;
import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session started by logging in with a username and password. It holds the short-lived access
 * token and the refresh token, refreshes the access token shortly before it expires, and logs in
 * again when the server has ended the session.
 * <p>
 * Thread-safe: requests read the current token without locking; logins and refreshes are
 * serialized, and a token that another thread has already replaced is not refreshed again.
 */
final class LoginSession {

    /** How long before the expiry of the access token it is refreshed. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private static final int MAX_RETIRED_TOKENS = 4;
    private static final System.Logger LOG = System.getLogger(DaktelaConnector.class.getName());

    /** Sends a login or refresh request; implemented by the connector. */
    interface Transport {
        /**
         * @param endpoint     "login" or "refreshAccessToken"
         * @param payload      the request body, which carries the credentials
         * @param extraSecret  a secret to redact from errors besides the tokens, or null
         */
        DaktelaResponse post(String endpoint, Map<String, Object> payload, String extraSecret);
    }

    private final String username;
    private final String password;
    private final String owner;
    private final TokenStore tokenStore;
    private final Clock clock;
    private final Object lock = new Object();

    private volatile TokenSet tokens;
    private volatile List<String> retiredTokens = List.of();
    private volatile boolean refreshBeforeExpiry = true;
    /** HTTP status of a login the server rejected, or 0. No further login is sent after one. */
    private volatile int rejectedStatus;

    LoginSession(String username, String password, String owner, TokenStore tokenStore, Clock clock) {
        this.username = username;
        this.password = password;
        this.owner = owner;
        this.tokenStore = tokenStore;
        this.clock = clock;
    }

    /**
     * Returns the access token to send, logging in or refreshing it first when needed.
     */
    String accessToken(Transport transport) {
        TokenSet current = tokens;
        if (current == null) {
            synchronized (lock) {
                if (tokens == null) {
                    start(transport);
                }
                return tokens.getAccessToken();
            }
        }
        if (!refreshBeforeExpiry || !current.accessTokenExpiresWithin(REFRESH_MARGIN, clock.instant())) {
            return current.getAccessToken();
        }
        synchronized (lock) {
            if (tokens == current) {
                try {
                    refresh(transport);
                } catch (DaktelaException e) {
                    TokenSet left = tokens;
                    if (left == null || left.accessTokenExpiresWithin(Duration.ZERO, clock.instant())) {
                        throw e;
                    }
                    LOG.log(System.Logger.Level.WARNING,
                            "Refreshing the Daktela access token failed; the current one is used until it expires: {0}",
                            e.getMessage());
                }
            }
            return tokens.getAccessToken();
        }
    }

    /**
     * Refreshes the session after the server answered 401 to a request sent with the given token,
     * unless another thread has already replaced that token.
     */
    void refreshAfterUnauthorized(String rejectedToken, Transport transport) {
        synchronized (lock) {
            TokenSet current = tokens;
            if (current == null || current.getAccessToken().equals(rejectedToken)) {
                refresh(transport);
            }
        }
    }

    /** Refreshes the session now, for example when the server has ended it without answering 401. */
    void refreshNow(Transport transport) {
        synchronized (lock) {
            refresh(transport);
        }
    }

    /** The tokens of this session, including recently replaced ones, which responses may still echo. */
    List<String> secrets() {
        List<String> secrets = new ArrayList<>(retiredTokens);
        TokenSet current = tokens;
        if (current != null) {
            secrets.add(current.getAccessToken());
            secrets.add(current.getRefreshToken());
        }
        return secrets;
    }

    private void start(Transport transport) {
        TokenSet stored = loadStoredTokens();
        if (stored != null) {
            use(stored, false);
        } else {
            login(transport);
        }
    }

    private void login(Transport transport) {
        int rejected = rejectedStatus;
        if (rejected != 0) {
            String message = "The server rejected the login (HTTP " + rejected
                    + "); build a new connector with valid credentials";
            throw rejected == 401
                    ? new DaktelaUnauthorizedException(message, null, null)
                    : new DaktelaException(message, rejected);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        // Return the tokens instead of setting them as cookies.
        payload.put("only_token", "true");
        // The connector refreshes the session itself; the server does not renew it implicitly.
        payload.put("client_side_refresh", "true");
        TokenSet received;
        try {
            // An error response to a login may echo the password, so it is redacted as well.
            received = tokensFrom(transport.post("login", payload, password), null, "login");
        } catch (DaktelaException e) {
            if (e.getStatusCode() == 400 || e.getStatusCode() == 401) {
                // Repeating a rejected login could lock the account.
                rejectedStatus = e.getStatusCode();
                retire(tokens);
                tokens = null;
            }
            throw e;
        }
        use(received, true);
        saveTokens();
    }

    private void refresh(Transport transport) {
        TokenSet current = tokens;
        if (current == null || current.isRefreshTokenExpired(clock.instant())) {
            login(transport);
            return;
        }
        TokenSet received;
        try {
            received = tokensFrom(transport.post("refreshAccessToken",
                    Map.of("refresh_token", current.getRefreshToken()), null), current, "refreshAccessToken");
        } catch (DaktelaException e) {
            if (e.getStatusCode() != 400 && e.getStatusCode() != 401) {
                throw e;
            }
            // The server has ended the session.
            login(transport);
            return;
        }
        use(received, true);
        saveTokens();
    }

    private TokenSet tokensFrom(DaktelaResponse response, TokenSet current, String endpoint) {
        Map<String, Object> result = response.getDataAsMap();
        Object accessToken = result.get("accessToken");
        Object refreshToken = result.get("refreshToken");
        if (current != null && (refreshToken == null || "".equals(refreshToken))) {
            // The server keeps the refresh token when it does not rotate it.
            refreshToken = current.getRefreshToken();
        }
        if (!(accessToken instanceof String) || ((String) accessToken).isEmpty()
                || !(refreshToken instanceof String) || ((String) refreshToken).isEmpty()) {
            throw new DaktelaException("The " + endpoint + " response (HTTP " + response.getStatus()
                    + ") does not contain an access token and a refresh token", response.getStatus());
        }
        if (!DaktelaConnector.isHeaderSafe((String) accessToken) || !DaktelaConnector.isHeaderSafe((String) refreshToken)) {
            // The message must not quote the token.
            throw new DaktelaException("The " + endpoint + " response (HTTP " + response.getStatus()
                    + ") contains a token that cannot be sent in a header", response.getStatus());
        }
        return TokenSet.of((String) accessToken, (String) refreshToken, owner);
    }

    private void use(TokenSet received, boolean issuedNow) {
        retire(tokens);
        tokens = received;
        // A token that looks expired as soon as it is issued means the local clock is ahead of the
        // server. Refreshing it before every request would not help, so it is refreshed after a 401.
        refreshBeforeExpiry = !issuedNow || !received.accessTokenExpiresWithin(REFRESH_MARGIN, clock.instant());
    }

    private void retire(TokenSet replaced) {
        if (replaced == null) {
            return;
        }
        List<String> retired = new ArrayList<>();
        retired.add(replaced.getAccessToken());
        retired.add(replaced.getRefreshToken());
        for (String token : retiredTokens) {
            if (!retired.contains(token)) {
                retired.add(token);
            }
        }
        retiredTokens = List.copyOf(retired.subList(0, Math.min(retired.size(), MAX_RETIRED_TOKENS)));
    }

    private TokenSet loadStoredTokens() {
        if (tokenStore == null) {
            return null;
        }
        TokenSet stored;
        try {
            stored = tokenStore.load();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Loading the saved Daktela login session failed; logging in: {0}",
                    Redactor.of(secrets()).redact(String.valueOf(e)));
            return null;
        }
        // A store shared by several users or instances must not resume another session.
        if (stored == null || !owner.equals(stored.getOwner()) || stored.isRefreshTokenExpired(clock.instant())
                || !DaktelaConnector.isHeaderSafe(stored.getAccessToken())
                || !DaktelaConnector.isHeaderSafe(stored.getRefreshToken())) {
            return null;
        }
        return stored;
    }

    private void saveTokens() {
        if (tokenStore == null) {
            return;
        }
        try {
            tokenStore.save(tokens);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Saving the Daktela login session failed; it is kept in memory: {0}",
                    Redactor.of(secrets()).redact(String.valueOf(e)));
        }
    }
}
