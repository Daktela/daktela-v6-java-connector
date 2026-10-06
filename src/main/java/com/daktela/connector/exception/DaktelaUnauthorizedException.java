package com.daktela.connector.exception;

/**
 * Exception thrown when authentication fails (HTTP 401).
 */
public class DaktelaUnauthorizedException extends DaktelaException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public DaktelaUnauthorizedException(String message) {
        super(message, 401);
    }

    /**
     * Creates the exception with the API's error payload.
     *
     * @param message   the detail message
     * @param errorData the {@code error} payload from the API response
     */
    public DaktelaUnauthorizedException(String message, Object errorData) {
        super(message, 401, errorData);
    }

    /**
     * Creates the exception with the API's error payload and raw response body.
     *
     * @param message      the detail message
     * @param errorData    the {@code error} payload from the API response
     * @param responseBody the raw response body
     */
    public DaktelaUnauthorizedException(String message, Object errorData, String responseBody) {
        super(message, 401, errorData, responseBody, null);
    }
}
