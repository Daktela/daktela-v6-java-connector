package com.daktela.connector.auth;

/**
 * Keeps the tokens of a login session outside the connector, so that several processes, or a
 * restarted one, can reuse a session instead of logging in again: every login starts a new session
 * on the Daktela server.
 * <p>
 * Implement it with your cache or database, for example by saving the two tokens and the owner and
 * restoring them with {@link TokenSet#of(String, String, String)}. Refresh tokens are long-lived
 * credentials, so store them as securely as passwords. The connector calls the store from the
 * thread that sends a request; when the store throws, the connector logs a warning and continues
 * with the tokens it holds in memory.
 * <p>
 * The connector checks only that saved tokens belong to the same username and instance; it does
 * not check the password against them. Whoever can build a connector that reads a store therefore
 * gets its session, so give each party that holds a password its own store (for example one per
 * tenant), never one shared by several of them.
 */
public interface TokenStore {

    /**
     * Loads saved tokens. The connector ignores tokens of another session (see
     * {@link TokenSet#getOwner()}) and tokens whose refresh token has expired, and logs in instead.
     *
     * @return the saved tokens, or null when none are saved
     */
    TokenSet load();

    /**
     * Saves new tokens after a login or a refresh.
     *
     * @param tokens the tokens to save
     */
    void save(TokenSet tokens);
}
