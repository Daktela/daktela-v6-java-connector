package com.daktela.connector.exception;

import java.time.Duration;

/**
 * Exception thrown when the API rejects a request because of rate limiting (HTTP 429).
 */
public class DaktelaRateLimitException extends DaktelaException {

    /** Wait requested by the server, or null. */
    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param message      the detail message
     * @param retryAfter   the wait requested by the server, or null
     * @param errorData    the {@code error} payload from the API response
     * @param responseBody the raw response body
     */
    public DaktelaRateLimitException(String message, Duration retryAfter, Object errorData, String responseBody) {
        super(message, 429, errorData, responseBody, null);
        this.retryAfter = retryAfter;
    }

    /**
     * Returns how long the server asked the client to wait, from the {@code Retry-After} header.
     *
     * @return the wait duration, or null if the server did not say
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
