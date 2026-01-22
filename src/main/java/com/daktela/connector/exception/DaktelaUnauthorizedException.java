package com.daktela.connector.exception;

/**
 * Exception thrown when authentication fails (HTTP 401).
 */
public class DaktelaUnauthorizedException extends DaktelaException {

    public DaktelaUnauthorizedException(String message) {
        super(message, 401);
    }

    public DaktelaUnauthorizedException(String message, Object errorData) {
        super(message, 401, errorData);
    }
}
