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
     * Send access token as query parameter. The token then appears in URLs and can end up in
     * proxy and server access logs, so prefer a header-based method.
     */
    QUERY,

    /**
     * Send access token in an {@code Authorization: Bearer} header.
     */
    BEARER
}
