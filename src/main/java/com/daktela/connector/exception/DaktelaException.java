package com.daktela.connector.exception;

/**
 * Base exception for all Daktela API errors.
 */
public class DaktelaException extends RuntimeException {

    /** HTTP status code, or 0 if not applicable. */
    private final int statusCode;
    // Parsed JSON (maps, lists, strings) - not guaranteed to be serializable.
    private final transient Object errorData;
    /** Raw response body, or null. */
    private final String responseBody;

    /**
     * Creates an exception without HTTP context.
     *
     * @param message the detail message
     */
    public DaktelaException(String message) {
        this(message, 0, null, null, null);
    }

    /**
     * Creates an exception for an HTTP status.
     *
     * @param message    the detail message
     * @param statusCode the HTTP status code
     */
    public DaktelaException(String message, int statusCode) {
        this(message, statusCode, null, null, null);
    }

    /**
     * Creates an exception for an HTTP status with the API's error payload.
     *
     * @param message    the detail message
     * @param statusCode the HTTP status code
     * @param errorData  the {@code error} payload from the API response
     */
    public DaktelaException(String message, int statusCode, Object errorData) {
        this(message, statusCode, errorData, null, null);
    }

    /**
     * Creates an exception caused by another failure, such as a network error.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public DaktelaException(String message, Throwable cause) {
        this(message, 0, null, null, cause);
    }

    /**
     * Creates an exception for an HTTP status caused by another failure.
     *
     * @param message    the detail message
     * @param statusCode the HTTP status code
     * @param cause      the cause
     */
    public DaktelaException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, null, null, cause);
    }

    /**
     * Creates an exception carrying the full HTTP error context.
     *
     * @param message      the detail message
     * @param statusCode   the HTTP status code, or 0 if not applicable
     * @param errorData    the {@code error} payload from the API response, or null
     * @param responseBody the raw response body, or null
     * @param cause        the cause, or null
     */
    public DaktelaException(String message, int statusCode, Object errorData, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorData = errorData;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code associated with this exception.
     *
     * @return HTTP status code, or 0 if not applicable
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the error data from the API response.
     *
     * @return error data object, or null if not available
     */
    public Object getErrorData() {
        return errorData;
    }

    /**
     * Returns the raw response body, useful when the server returned something that is not
     * valid API JSON (for example an HTML error page from a proxy).
     *
     * @return the response body, or null if there was no response
     */
    public String getResponseBody() {
        return responseBody;
    }
}
