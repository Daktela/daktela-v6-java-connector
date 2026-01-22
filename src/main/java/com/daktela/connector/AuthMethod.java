package com.daktela.connector;

/**
 * Authentication method for Daktela API requests.
 */
public enum AuthMethod {
    /**
     * Send access token in X-AUTH-TOKEN header.
     */
    HEADER,

    /**
     * Send access token as query parameter.
     */
    QUERY
}
