package com.daktela.connector;

import com.daktela.connector.auth.TokenSet;
import com.daktela.connector.auth.TokenStore;
import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaForbiddenException;
import com.daktela.connector.exception.DaktelaNotFoundException;
import com.daktela.connector.exception.DaktelaRateLimitException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    /** How long to wait before trying again after a refresh failed while the token was still valid. */
    static final Duration REFRESH_RETRY_DELAY = Duration.ofSeconds(15);
    /**
     * How long to wait before logging in again after a login failed for a reason other than
     * rejection. It doubles with each failure in a row, up to {@link #MAX_LOGIN_BACKOFF}.
     */
    static final Duration LOGIN_RETRY_DELAY = Duration.ofSeconds(10);
    /** The longest wait after failed logins in a row, unless the server asks for more. */
    static final Duration MAX_LOGIN_BACKOFF = Duration.ofMinutes(5);
    /** The longest wait a Retry-After of a rate-limited login can impose. */
    static final Duration MAX_LOGIN_RETRY_DELAY = Duration.ofHours(1);

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
    private volatile Instant refreshNotBefore = Instant.MIN;
    /** HTTP status of a login the server rejected, or 0. No further login is sent after one. */
    private volatile int rejectedStatus;
    /** Why that login was rejected (already redacted). */
    private volatile String rejectedReason;
    /** After a failed login: when the next one may be sent, and the failure to report until then. */
    private Instant loginNotBefore = Instant.MIN;
    private DaktelaException lastLoginFailure;
    /** Logins interrupted in a row since the last completed one. */
    private int interruptedLogins;
    /** Logins that failed in a row, other than rejections, since the last successful one. */
    private int failedLogins;
    /** Refresh attempts finished so far, and the failure of the last one (null after a success). */
    private volatile long refreshAttempts;
    private DaktelaException lastRefreshFailure;

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
        long attemptsSeen = refreshAttempts;
        TokenSet current = tokens;
        if (current != null && !expiresSoon(current)) {
            return current.getAccessToken();
        }
        synchronized (lock) {
            // Another thread may have logged in or refreshed while this one waited.
            if (tokens == null) {
                start(transport);
            }
            if (expiresSoon(tokens)) {
                try {
                    refreshUnlessJustFailed(transport, attemptsSeen);
                } catch (DaktelaException e) {
                    TokenSet left = tokens;
                    if (left == null || expiresWithin(left.getAccessTokenExpiresAt(), Duration.ZERO)) {
                        throw e;
                    }
                    // Without a pause every request, one after another under the lock, would wait for
                    // the failing refresh although the current token still works.
                    refreshNotBefore = clock.instant().plus(REFRESH_RETRY_DELAY);
                    LOG.log(System.Logger.Level.WARNING,
                            "Refreshing the Daktela access token failed; the current one is used until it expires: {0}",
                            e.getMessage());
                }
            }
            return tokens.getAccessToken();
        }
    }

    private boolean expiresSoon(TokenSet current) {
        if (!refreshBeforeExpiry) {
            return false;
        }
        Instant expiresAt = current.getAccessTokenExpiresAt();
        return expiresWithin(expiresAt, Duration.ZERO)
                || (expiresWithin(expiresAt, REFRESH_MARGIN) && !clock.instant().isBefore(refreshNotBefore));
    }

    /** Whether the expiry is within the margin from now; false when the expiry is unknown. */
    private boolean expiresWithin(Instant expiresAt, Duration margin) {
        return expiresAt != null && !expiresAt.isAfter(clock.instant().plus(margin));
    }

    /**
     * Refreshes the session after the server answered 401 to a request sent with the given token,
     * unless another thread has already replaced that token.
     */
    void refreshAfterUnauthorized(String rejectedToken, Transport transport) {
        long attemptsSeen = refreshAttempts;
        synchronized (lock) {
            TokenSet current = tokens;
            if (current == null || current.getAccessToken().equals(rejectedToken)) {
                refreshUnlessJustFailed(transport, attemptsSeen);
            }
        }
    }

    /** Refreshes the session now, for example when the server has ended it without answering 401. */
    void refreshNow(Transport transport) {
        long attemptsSeen = refreshAttempts;
        synchronized (lock) {
            refreshUnlessJustFailed(transport, attemptsSeen);
        }
    }

    /**
     * Refreshes the session, unless a refresh that another thread attempted while this one waited
     * for the lock has failed: trying again at once would make every waiting caller wait for the
     * same failure in turn. Called under the lock.
     */
    private void refreshUnlessJustFailed(Transport transport, long attemptsSeen) {
        DaktelaException failure = lastRefreshFailure;
        if (refreshAttempts != attemptsSeen && failure != null) {
            throw failedRecently("Refreshing the access token failed just now", failure, null);
        }
        try {
            refresh(transport);
            lastRefreshFailure = null;
        } catch (DaktelaException e) {
            lastRefreshFailure = e;
            throw e;
        } finally {
            refreshAttempts++;
        }
    }

    /** The tokens of this session, including recently replaced ones, which responses may still echo. */
    List<String> secrets() {
        // use() writes retiredTokens before tokens, so reading them in the opposite order cannot miss
        // a token that is being replaced.
        TokenSet current = tokens;
        List<String> secrets = new ArrayList<>(retiredTokens);
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
            String message = "The server rejected the login (" + rejectedReason
                    + "); build a new connector with valid credentials";
            if (rejected == 401) {
                throw new DaktelaUnauthorizedException(message, null, null);
            }
            throw rejected == 403
                    ? new DaktelaForbiddenException(message, null, null)
                    : new DaktelaException(message, rejected);
        }
        Instant now = clock.instant();
        if (now.isBefore(loginNotBefore)) {
            // Sending the password with every request while the server is failing or rate-limiting
            // logins would keep an account or IP blocked.
            throw coolingDown(Duration.between(now, loginNotBefore));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        // Return the tokens instead of setting them as cookies.
        payload.put("only_token", "true");
        // The connector refreshes the session itself; the server does not renew it implicitly.
        payload.put("client_side_refresh", "true");
        DaktelaResponse response;
        try {
            // An error response to a login may echo the password, so it is redacted as well.
            response = transport.post("login", payload, password);
        } catch (DaktelaException e) {
            int status = e.getStatusCode();
            if (e.getCause() instanceof InterruptedException && ++interruptedLogins < 2) {
                // One caller giving up should not make every other caller wait. The server may still
                // have received the login, though, so repeated interruptions do start the cool-down.
                throw e;
            }
            if (status == 400 || status == 401 || status == 403) {
                // Rejected credentials or a blocked account: repeating the login could lock the account.
                reject(e);
            } else {
                loginNotBefore = clock.instant().plus(loginBackoff(e));
                lastLoginFailure = e;
            }
            throw e;
        }
        TokenSet received;
        try {
            received = tokensFrom(response, null, "login");
        } catch (DaktelaException e) {
            // An answer without tokens, for example a second factor the connector cannot provide,
            // would be the same next time.
            reject(e);
            throw e;
        }
        loginNotBefore = Instant.MIN;
        lastLoginFailure = null;
        interruptedLogins = 0;
        failedLogins = 0;
        use(received, true);
        saveTokens();
    }

    private void reject(DaktelaException e) {
        rejectedReason = e.getMessage();
        rejectedStatus = e.getStatusCode();
        retire(tokens);
        tokens = null;
    }

    /**
     * How long to wait after a login that failed without being rejected: 10 s, doubling with each
     * failure in a row up to 5 minutes, or longer when the server asks for it with Retry-After
     * (up to an hour, so that a huge value cannot overflow the clock).
     */
    private Duration loginBackoff(DaktelaException e) {
        failedLogins++;
        Duration backoff = LOGIN_RETRY_DELAY.multipliedBy(1L << Math.min(failedLogins - 1, 5));
        if (backoff.compareTo(MAX_LOGIN_BACKOFF) > 0) {
            backoff = MAX_LOGIN_BACKOFF;
        }
        Duration retryAfter = e instanceof DaktelaRateLimitException ? ((DaktelaRateLimitException) e).getRetryAfter() : null;
        if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter.compareTo(MAX_LOGIN_RETRY_DELAY) > 0 ? MAX_LOGIN_RETRY_DELAY : retryAfter;
        }
        return backoff;
    }

    private DaktelaException coolingDown(Duration remaining) {
        return failedRecently("Logging in failed recently", lastLoginFailure, remaining);
    }

    /**
     * A new exception for a recent failure that is reported again without sending a request. It
     * keeps the kind and status of the original, so that callers' handlers still match, and chains
     * the original as the cause where the kind allows one.
     */
    private static DaktelaException failedRecently(String what, DaktelaException last, Duration remaining) {
        String message = what + " (" + last.getMessage() + ")"
                + (remaining != null ? "; the next login is sent in " + Math.max(1, remaining.toSeconds()) + " s" : "");
        if (last instanceof DaktelaRateLimitException) {
            return new DaktelaRateLimitException(message,
                    remaining != null ? remaining : ((DaktelaRateLimitException) last).getRetryAfter(), null, null);
        }
        if (last instanceof DaktelaUnauthorizedException) {
            return new DaktelaUnauthorizedException(message, null, null);
        }
        if (last instanceof DaktelaForbiddenException) {
            return new DaktelaForbiddenException(message, null, null);
        }
        if (last instanceof DaktelaNotFoundException) {
            return new DaktelaNotFoundException(message, null, null);
        }
        return new DaktelaException(message, last.getStatusCode(), last);
    }

    private void refresh(Transport transport) {
        TokenSet current = tokens;
        if (current != null && !expiresWithin(current.getRefreshTokenExpiresAt(), Duration.ZERO)) {
            TokenSet received;
            try {
                received = tokensFrom(transport.post("refreshAccessToken",
                        Map.of("refresh_token", current.getRefreshToken()), null), current, "refreshAccessToken");
            } catch (DaktelaException e) {
                if (e.getStatusCode() != 400 && e.getStatusCode() != 401) {
                    throw e;
                }
                received = null;
                // The server has ended the session; it is replaced below.
            }
            if (received != null) {
                use(received, true);
                saveTokens();
                return;
            }
        }
        // The session cannot be refreshed. Its tokens are of no further use, so drop them: requests
        // must not keep sending them while a new login is not possible yet. Another process sharing
        // the token store may already have started a newer session; resume that before logging in.
        retire(tokens);
        tokens = null;
        start(transport);
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
        refreshBeforeExpiry = !issuedNow || !expiresWithin(received.getAccessTokenExpiresAt(), REFRESH_MARGIN);
        refreshNotBefore = Instant.MIN;
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
            // Only the type: the message may quote what the store read, including tokens.
            LOG.log(System.Logger.Level.WARNING, "Loading the saved Daktela login session failed ({0}); logging in",
                    e.getClass().getName());
            return null;
        }
        // A store shared by several users or instances must not resume another session, nor this
        // session's own tokens after it replaced them or the server ended it.
        if (stored == null || !owner.equals(stored.getOwner())
                || expiresWithin(stored.getRefreshTokenExpiresAt(), Duration.ZERO)
                || retiredTokens.contains(stored.getRefreshToken())
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
