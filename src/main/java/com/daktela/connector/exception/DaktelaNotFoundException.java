package com.daktela.connector.exception;

/**
 * Exception thrown when a requested resource is not found (HTTP 404).
 */
public class DaktelaNotFoundException extends DaktelaException {

    public DaktelaNotFoundException(String message) {
        super(message, 404);
    }

    public DaktelaNotFoundException(String message, Object errorData) {
        super(message, 404, errorData);
    }
}
