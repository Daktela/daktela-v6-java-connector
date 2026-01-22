package com.daktela.connector.exception;

/**
 * Base exception for all Daktela API errors.
 */
public class DaktelaException extends RuntimeException {

    private final int statusCode;
    private final Object errorData;

    public DaktelaException(String message) {
        super(message);
        this.statusCode = 0;
        this.errorData = null;
    }

    public DaktelaException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorData = null;
    }

    public DaktelaException(String message, int statusCode, Object errorData) {
        super(message);
        this.statusCode = statusCode;
        this.errorData = errorData;
    }

    public DaktelaException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorData = null;
    }

    public DaktelaException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorData = null;
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
}
