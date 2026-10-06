package com.daktela.connector.exception;

/**
 * Exception thrown when the access token lacks permission for the request (HTTP 403).
 */
public class DaktelaForbiddenException extends DaktelaException {

    /**
     * Creates the exception with the API's error payload and raw response body.
     *
     * @param message      the detail message
     * @param errorData    the {@code error} payload from the API response
     * @param responseBody the raw response body
     */
    public DaktelaForbiddenException(String message, Object errorData, String responseBody) {
        super(message, 403, errorData, responseBody, null);
    }
}
