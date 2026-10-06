package com.daktela.connector.exception;

/**
 * Exception thrown when a requested resource is not found (HTTP 404).
 */
public class DaktelaNotFoundException extends DaktelaException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public DaktelaNotFoundException(String message) {
        super(message, 404);
    }

    /**
     * Creates the exception with the API's error payload.
     *
     * @param message   the detail message
     * @param errorData the {@code error} payload from the API response
     */
    public DaktelaNotFoundException(String message, Object errorData) {
        super(message, 404, errorData);
    }

    /**
     * Creates the exception with the API's error payload and raw response body.
     *
     * @param message      the detail message
     * @param errorData    the {@code error} payload from the API response
     * @param responseBody the raw response body
     */
    public DaktelaNotFoundException(String message, Object errorData, String responseBody) {
        super(message, 404, errorData, responseBody, null);
    }
}
