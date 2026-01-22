package com.daktela.connector;

import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaNotFoundException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main client for Daktela V6 REST API.
 * <p>
 * Example usage:
 * <pre>{@code
 * DaktelaConnector connector = DaktelaConnector.builder()
 *     .instance("my.daktela.com")
 *     .accessToken("your-token")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 *
 * DaktelaResponse response = connector.get("tickets",
 *     DaktelaQuery.builder()
 *         .filter(DaktelaFilter.eq("stage", "OPEN"))
 *         .build());
 * }</pre>
 */
public class DaktelaConnector {

    private static final String API_PATH = "/api/v6/";
    private static final String DEFAULT_USER_AGENT = "DaktelaJavaConnector/1.0";

    private final String instance;
    private final String accessToken;
    private final Duration timeout;
    private final AuthMethod authMethod;
    private final String userAgent;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private DaktelaConnector(Builder builder) {
        this.instance = builder.instance;
        this.accessToken = builder.accessToken;
        this.timeout = builder.timeout;
        this.authMethod = builder.authMethod;
        this.userAgent = builder.userAgent;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /**
     * Creates a new builder for DaktelaConnector.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Performs a GET request.
     *
     * @param endpoint the API endpoint (e.g., "tickets" or "tickets/123")
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse get(String endpoint) {
        return get(endpoint, null);
    }

    /**
     * Performs a GET request with query parameters.
     *
     * @param endpoint the API endpoint
     * @param query    the query parameters
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse get(String endpoint, DaktelaQuery query) {
        String url = buildUrl(endpoint, query);
        HttpRequest request = buildRequest(url, "GET", null);
        return executeRequest(request);
    }

    /**
     * Performs a POST request.
     *
     * @param endpoint the API endpoint
     * @param data     the request body
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse post(String endpoint, Map<String, Object> data) {
        String url = buildUrl(endpoint, null);
        HttpRequest request = buildRequest(url, "POST", data);
        return executeRequest(request);
    }

    /**
     * Performs a PUT request.
     *
     * @param endpoint the API endpoint
     * @param data     the request body
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse put(String endpoint, Map<String, Object> data) {
        String url = buildUrl(endpoint, null);
        HttpRequest request = buildRequest(url, "PUT", data);
        return executeRequest(request);
    }

    /**
     * Performs a DELETE request.
     *
     * @param endpoint the API endpoint
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse delete(String endpoint) {
        String url = buildUrl(endpoint, null);
        HttpRequest request = buildRequest(url, "DELETE", null);
        return executeRequest(request);
    }

    private String buildUrl(String endpoint, DaktelaQuery query) {
        StringBuilder url = new StringBuilder();
        url.append("https://").append(instance).append(API_PATH);

        // Remove leading slash if present
        if (endpoint.startsWith("/")) {
            endpoint = endpoint.substring(1);
        }
        url.append(endpoint);

        // Build query parameters
        List<String> params = new ArrayList<>();

        // Add auth token as query param if using QUERY auth method
        if (authMethod == AuthMethod.QUERY) {
            params.add("accessToken=" + urlEncode(accessToken));
        }

        if (query != null) {
            // Fields
            if (!query.getFields().isEmpty()) {
                for (int i = 0; i < query.getFields().size(); i++) {
                    params.add("fields[" + i + "]=" + urlEncode(query.getFields().get(i)));
                }
            }

            // Filters
            if (!query.getFilters().isEmpty()) {
                for (int i = 0; i < query.getFilters().size(); i++) {
                    DaktelaFilter filter = query.getFilters().get(i);
                    addFilterParams(params, filter, i);
                }
            }

            // Sorts
            if (!query.getSorts().isEmpty()) {
                for (int i = 0; i < query.getSorts().size(); i++) {
                    DaktelaSort sort = query.getSorts().get(i);
                    params.add("sort[" + i + "][field]=" + urlEncode(sort.getField()));
                    params.add("sort[" + i + "][dir]=" + urlEncode(sort.getDirection()));
                }
            }

            // Pagination
            if (query.getTake() != null) {
                params.add("take=" + query.getTake());
            }
            if (query.getSkip() != null) {
                params.add("skip=" + query.getSkip());
            }
        }

        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }

        return url.toString();
    }

    private void addFilterParams(List<String> params, DaktelaFilter filter, int index) {
        if (filter.isOr() && filter.getOrFilters() != null) {
            List<DaktelaFilter> orFilters = filter.getOrFilters();
            for (int j = 0; j < orFilters.size(); j++) {
                DaktelaFilter f = orFilters.get(j);
                String prefix = "filter[" + index + "][or][" + j + "]";
                params.add(prefix + "[field]=" + urlEncode(f.getField()));
                params.add(prefix + "[operator]=" + urlEncode(f.getOperator()));
                addFilterValue(params, prefix, f.getValue());
            }
        } else {
            String prefix = "filter[" + index + "]";
            params.add(prefix + "[field]=" + urlEncode(filter.getField()));
            params.add(prefix + "[operator]=" + urlEncode(filter.getOperator()));
            addFilterValue(params, prefix, filter.getValue());
        }
    }

    private void addFilterValue(List<String> params, String prefix, Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                params.add(prefix + "[value][" + i + "]=" + urlEncode(String.valueOf(list.get(i))));
            }
        } else {
            params.add(prefix + "[value]=" + urlEncode(String.valueOf(value)));
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpRequest buildRequest(String url, String method, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);

        // Add auth header if using HEADER auth method
        if (authMethod == AuthMethod.HEADER) {
            builder.header("X-AUTH-TOKEN", accessToken);
        }

        if (body != null) {
            try {
                String jsonBody = objectMapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } catch (JsonProcessingException e) {
                throw new DaktelaException("Failed to serialize request body", e);
            }
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private DaktelaResponse executeRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            Object data = null;
            Integer total = null;
            List<Object> errors = null;

            String responseBody = response.body();
            if (responseBody != null && !responseBody.isEmpty()) {
                Map<String, Object> json = objectMapper.readValue(responseBody, Map.class);

                // Extract result/data
                if (json.containsKey("result")) {
                    Object result = json.get("result");
                    if (result instanceof Map) {
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        data = resultMap.get("data");
                        if (resultMap.containsKey("total")) {
                            total = ((Number) resultMap.get("total")).intValue();
                        }
                    } else {
                        data = result;
                    }
                } else if (json.containsKey("data")) {
                    data = json.get("data");
                }

                // Extract total if at top level
                if (total == null && json.containsKey("total")) {
                    total = ((Number) json.get("total")).intValue();
                }

                // Extract errors
                if (json.containsKey("error")) {
                    Object error = json.get("error");
                    if (error instanceof List) {
                        errors = (List<Object>) error;
                    } else if (error != null) {
                        errors = List.of(error);
                    }
                }
                if (json.containsKey("errors")) {
                    Object errorList = json.get("errors");
                    if (errorList instanceof List) {
                        errors = (List<Object>) errorList;
                    }
                }
            }

            // Throw appropriate exception for error status codes
            if (statusCode == 401) {
                throw new DaktelaUnauthorizedException("Unauthorized", errors);
            }
            if (statusCode == 404) {
                throw new DaktelaNotFoundException("Not found", errors);
            }
            if (statusCode >= 400) {
                String message = errors != null && !errors.isEmpty() ? errors.toString() : "Request failed";
                throw new DaktelaException(message, statusCode, errors);
            }

            return new DaktelaResponse(statusCode, data, total, errors, objectMapper);

        } catch (DaktelaException e) {
            throw e;
        } catch (IOException e) {
            throw new DaktelaException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaktelaException("Request interrupted", e);
        }
    }

    /**
     * Builder for DaktelaConnector.
     */
    public static class Builder {
        private String instance;
        private String accessToken;
        private Duration timeout = Duration.ofSeconds(30);
        private AuthMethod authMethod = AuthMethod.HEADER;
        private String userAgent = DEFAULT_USER_AGENT;

        private Builder() {
        }

        /**
         * Sets the Daktela instance hostname.
         *
         * @param instance the instance hostname (e.g., "my.daktela.com")
         * @return this builder
         */
        public Builder instance(String instance) {
            this.instance = instance;
            return this;
        }

        /**
         * Sets the access token for authentication.
         *
         * @param accessToken the access token
         * @return this builder
         */
        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        /**
         * Sets the request timeout.
         *
         * @param timeout the timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the authentication method.
         *
         * @param authMethod HEADER or QUERY
         * @return this builder
         */
        public Builder authMethod(AuthMethod authMethod) {
            this.authMethod = authMethod;
            return this;
        }

        /**
         * Sets the User-Agent header.
         *
         * @param userAgent the user agent string
         * @return this builder
         */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /**
         * Builds the connector.
         *
         * @return a new DaktelaConnector instance
         * @throws NullPointerException if required fields are missing
         */
        public DaktelaConnector build() {
            Objects.requireNonNull(instance, "instance is required");
            Objects.requireNonNull(accessToken, "accessToken is required");
            return new DaktelaConnector(this);
        }
    }
}
