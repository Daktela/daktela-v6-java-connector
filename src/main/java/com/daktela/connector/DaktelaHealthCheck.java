package com.daktela.connector;

import java.time.Duration;

/**
 * Result of {@link DaktelaConnector#healthCheck()}.
 * <p>
 * The API is healthy only when the {@code whoim} endpoint identifies the user of the access token.
 */
public final class DaktelaHealthCheck {

    private final boolean healthy;
    private final Duration latency;
    private final Integer statusCode;
    private final String error;

    DaktelaHealthCheck(boolean healthy, Duration latency, Integer statusCode, String error) {
        this.healthy = healthy;
        this.latency = latency;
        this.statusCode = statusCode;
        this.error = error;
    }

    /**
     * Returns whether the API is reachable and accepts the access token.
     *
     * @return true if healthy
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Returns how long the check took, including any token refresh and retries.
     *
     * @return the latency
     */
    public Duration getLatency() {
        return latency;
    }

    /**
     * Returns the HTTP status of the response.
     *
     * @return the status, or null when no response was received (for example on a network error)
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Returns why the API is not healthy: the first error message the API reported, or a
     * description of the failure. The access token is redacted from it.
     *
     * @return the error, or null when healthy
     */
    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "DaktelaHealthCheck{healthy=" + healthy
                + ", latency=" + latency.toMillis() + " ms"
                + ", statusCode=" + statusCode
                + ", error=" + error + '}';
    }
}
