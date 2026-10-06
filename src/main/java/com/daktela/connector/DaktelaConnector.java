package com.daktela.connector;

import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaForbiddenException;
import com.daktela.connector.exception.DaktelaNotFoundException;
import com.daktela.connector.exception.DaktelaRateLimitException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main client for Daktela V6 REST API.
 * <p>
 * Instances are immutable and thread-safe; create one per Daktela instance and reuse it.
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

    /** Date-time format used by the Daktela API. */
    public static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String API_PATH = "/api/v6/";
    private static final String JSON_SUFFIX = ".json";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_BODY_IN_MESSAGE = 500;
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    private final String baseUrl;
    private final String accessToken;
    private final Duration timeout;
    private final AuthMethod authMethod;
    private final String userAgent;
    private final int maxRetries;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    private DaktelaConnector(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.instance);
        this.accessToken = builder.accessToken;
        this.timeout = builder.timeout;
        this.authMethod = builder.authMethod;
        this.userAgent = builder.userAgent;
        this.maxRetries = builder.maxRetries;
        this.objectMapper = builder.objectMapper != null ? builder.objectMapper : defaultObjectMapper();
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.sleeper = builder.sleeper;
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
     * @param query    the query parameters, may be null
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse get(String endpoint, DaktelaQuery query) {
        return execute("GET", endpoint, query, null);
    }

    /**
     * Reads all records matching the query, following pagination until every page is fetched.
     * The query's {@code take} is used as the page size (default 100, API maximum 1000) and its
     * {@code skip} as the starting offset. Pages are read with offsets, so add a sort on a stable
     * field (e.g. {@code name}) when the data may change while you read it.
     *
     * @param endpoint the list endpoint (e.g., "tickets")
     * @param query    the query parameters, may be null
     * @return all matching records
     * @throws DaktelaException if any request fails
     */
    public List<Map<String, Object>> getAll(String endpoint, DaktelaQuery query) {
        DaktelaQuery base = query != null ? query : DaktelaQuery.builder().build();
        int pageSize = base.getTake() != null ? base.getTake() : DEFAULT_PAGE_SIZE;
        int skip = base.getSkip() != null ? base.getSkip() : 0;
        List<Map<String, Object>> records = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            DaktelaResponse response = get(endpoint, base.toBuilder().pagination(pageSize, skip).build());
            if (!(response.getData() instanceof List)) {
                return records;
            }
            List<Map<String, Object>> pageRecords = response.getDataAsList();
            records.addAll(pageRecords);
            skip += pageRecords.size();
            boolean lastPage = pageRecords.size() < pageSize
                    || (response.hasTotal() && skip >= response.getTotal());
            if (lastPage) {
                return records;
            }
        }
        throw new DaktelaException("Pagination of '" + endpoint + "' exceeded " + MAX_PAGES + " pages");
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
        return execute("POST", endpoint, null, data);
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
        return execute("PUT", endpoint, null, data);
    }

    /**
     * Performs a DELETE request.
     *
     * @param endpoint the API endpoint
     * @return the API response
     * @throws DaktelaException if the request fails
     */
    public DaktelaResponse delete(String endpoint) {
        return execute("DELETE", endpoint, null, null);
    }

    private DaktelaResponse execute(String method, String endpoint, DaktelaQuery query, Map<String, Object> body) {
        HttpRequest request = buildRequest(buildUrl(endpoint, query), method, body);
        boolean idempotent = "GET".equals(method);
        for (int attempt = 0; ; attempt++) {
            boolean retriesLeft = attempt < maxRetries;
            try {
                return send(request);
            } catch (DaktelaRateLimitException e) {
                // A 429 means the request was not processed, so retrying is safe for every method.
                Duration delay = e.getRetryAfter() != null ? e.getRetryAfter() : backoff(attempt);
                if (!retriesLeft || delay.compareTo(MAX_RETRY_DELAY) > 0) {
                    throw e;
                }
                sleep(delay);
            } catch (DaktelaException e) {
                if (!retriesLeft || !idempotent || !isTransient(e)) {
                    throw e;
                }
                sleep(backoff(attempt));
            }
        }
    }

    private static boolean isTransient(DaktelaException e) {
        int status = e.getStatusCode();
        return status == 502 || status == 503 || status == 504
                || (status == 0 && e.getCause() instanceof IOException);
    }

    private static Duration backoff(int attempt) {
        Duration delay = INITIAL_BACKOFF.multipliedBy(1L << Math.min(attempt, 10));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private void sleep(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaktelaException("Request interrupted", e);
        }
    }

    String buildUrl(String endpoint, DaktelaQuery query) {
        Objects.requireNonNull(endpoint, "endpoint is required");
        StringBuilder url = new StringBuilder(baseUrl).append(API_PATH).append(encodePath(endpoint));

        List<String> params = new ArrayList<>();
        if (authMethod == AuthMethod.QUERY) {
            params.add("accessToken=" + urlEncode(accessToken));
        }

        if (query != null) {
            List<String> fields = query.getFields();
            for (int i = 0; i < fields.size(); i++) {
                addParam(params, "fields[" + i + "]", fields.get(i));
            }

            List<DaktelaFilter> filters = query.getFilters();
            if (filters.size() == 1 && filters.get(0).isGroup()) {
                // A query whose only filter is a group is sent as that group, not wrapped in an extra AND.
                addFilterGroup(params, "filter", filters.get(0).getLogic(), filters.get(0).getFilters());
            } else if (!filters.isEmpty()) {
                addFilterGroup(params, "filter", "and", filters);
            }

            List<DaktelaSort> sorts = query.getSorts();
            for (int i = 0; i < sorts.size(); i++) {
                addParam(params, "sort[" + i + "][field]", sorts.get(i).getField());
                addParam(params, "sort[" + i + "][dir]", sorts.get(i).getDirection());
            }

            if (query.getTake() != null) {
                params.add("take=" + query.getTake());
            }
            if (query.getSkip() != null) {
                params.add("skip=" + query.getSkip());
            }

            for (Map.Entry<String, String> param : query.getParams().entrySet()) {
                addParam(params, param.getKey(), param.getValue());
            }
        }

        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }
        return url.toString();
    }

    private void addFilterGroup(List<String> params, String prefix, String logic, List<DaktelaFilter> filters) {
        addParam(params, prefix + "[logic]", logic);
        for (int i = 0; i < filters.size(); i++) {
            addFilter(params, prefix + "[filters][" + i + "]", filters.get(i));
        }
    }

    private void addFilter(List<String> params, String prefix, DaktelaFilter filter) {
        if (filter.isGroup()) {
            addFilterGroup(params, prefix, filter.getLogic(), filter.getFilters());
            return;
        }
        addParam(params, prefix + "[field]", filter.getField());
        addParam(params, prefix + "[operator]", filter.getOperator());
        Object value = filter.getValue();
        if (value instanceof Collection) {
            int i = 0;
            for (Object item : (Collection<?>) value) {
                addParam(params, prefix + "[value][" + i++ + "]", formatValue(item));
            }
        } else if (value != null) {
            addParam(params, prefix + "[value]", formatValue(value));
        }
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "1" : "0";
        }
        if (value instanceof LocalDateTime) {
            return DATE_TIME_FORMAT.format((LocalDateTime) value);
        }
        if (value instanceof LocalDate) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format((LocalDate) value);
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    private static void addParam(List<String> params, String name, String value) {
        params.add(urlEncode(name) + "=" + urlEncode(value));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Encodes each path segment of an endpoint and appends the {@code .json} suffix the API uses.
     */
    static String encodePath(String endpoint) {
        String path = endpoint.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(JSON_SUFFIX)) {
            path = path.substring(0, path.length() - JSON_SUFFIX.length());
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be empty");
        }
        if (path.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "endpoint must not contain a query string; use DaktelaQuery.Builder.param(name, value)");
        }
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid endpoint path: " + endpoint);
            }
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(encodePathSegment(segment));
        }
        return encoded.append(JSON_SUFFIX).toString();
    }

    private static String encodePathSegment(String segment) {
        // URLEncoder does form encoding; path segments need %20 for spaces and keep '@' and ':' readable.
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%40", "@")
                .replace("%3A", ":");
    }

    private HttpRequest buildRequest(String url, String method, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);

        if (authMethod == AuthMethod.HEADER) {
            builder.header("X-AUTH-TOKEN", accessToken);
        } else if (authMethod == AuthMethod.BEARER) {
            builder.header("Authorization", "Bearer " + accessToken);
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

    private DaktelaResponse send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DaktelaException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaktelaException("Request interrupted", e);
        }
        return parseResponse(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    DaktelaResponse parseResponse(int statusCode, String responseBody, String retryAfter) {
        Object json = null;
        DaktelaException parseError = null;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                json = objectMapper.readValue(responseBody, Object.class);
            } catch (JsonProcessingException e) {
                parseError = new DaktelaException("Invalid JSON in API response (HTTP " + statusCode + "): "
                        + abbreviate(responseBody), statusCode, null, responseBody, e);
            }
        }

        Object data = null;
        Integer total = null;
        List<Object> errors = null;

        if (json instanceof Map) {
            Map<?, ?> envelope = (Map<?, ?>) json;
            Object result = envelope.get("result");
            if (isListResult(result)) {
                // List responses: {"result": {"data": [...], "total": N}}
                Map<?, ?> resultMap = (Map<?, ?>) result;
                data = resultMap.get("data");
                total = toInteger(resultMap.get("total"));
            } else if (envelope.containsKey("result")) {
                // Single-record reads, creates and updates: {"result": {...record...}}
                data = result;
            } else if (envelope.containsKey("data")) {
                data = envelope.get("data");
            }
            if (total == null) {
                total = toInteger(envelope.get("total"));
            }

            errors = toErrorList(envelope.containsKey("error") ? envelope.get("error") : envelope.get("errors"));
        } else if (json != null) {
            data = json;
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw errorFor(statusCode, errors, responseBody, retryAfter);
        }
        if (parseError != null) {
            throw parseError;
        }
        return new DaktelaResponse(statusCode, data, total, errors, objectMapper);
    }

    private static boolean isListResult(Object result) {
        if (!(result instanceof Map)) {
            return false;
        }
        Map<?, ?> resultMap = (Map<?, ?>) result;
        // "total" is omitted only when the caller passes total=false; a record may itself have a "data" field.
        return resultMap.get("data") instanceof List && (resultMap.containsKey("total") || resultMap.size() == 1);
    }

    private static DaktelaException errorFor(int statusCode, List<Object> errors,
                                             String responseBody, String retryAfter) {
        String detail = errors != null && !errors.isEmpty()
                ? errors.toString()
                : (responseBody == null || responseBody.isBlank() ? null : abbreviate(responseBody));
        switch (statusCode) {
            case 401:
                return new DaktelaUnauthorizedException(message("Unauthorized", statusCode, detail),
                        errors, responseBody);
            case 403:
                return new DaktelaForbiddenException(message("Forbidden", statusCode, detail),
                        errors, responseBody);
            case 404:
                return new DaktelaNotFoundException(message("Not found", statusCode, detail),
                        errors, responseBody);
            case 429:
                return new DaktelaRateLimitException(message("Rate limit exceeded", statusCode, detail),
                        parseRetryAfter(retryAfter), errors, responseBody);
            default:
                return new DaktelaException(message("Request failed", statusCode, detail),
                        statusCode, errors, responseBody, null);
        }
    }

    private static String message(String summary, int statusCode, String detail) {
        return summary + " (HTTP " + statusCode + ")" + (detail != null ? ": " + detail : "");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toErrorList(Object errorData) {
        if (errorData == null) {
            return null;
        }
        if (errorData instanceof List) {
            return (List<Object>) errorData;
        }
        if (errorData instanceof Map && ((Map<?, ?>) errorData).isEmpty()) {
            return null;
        }
        if (errorData instanceof String && ((String) errorData).isEmpty()) {
            return null;
        }
        return List.of(errorData);
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.valueOf(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Duration parseRetryAfter(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        String value = retryAfter.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            // Not delta-seconds; try the HTTP-date form.
        }
        try {
            Duration delay = Duration.between(ZonedDateTime.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= MAX_BODY_IN_MESSAGE ? trimmed : trimmed.substring(0, MAX_BODY_IN_MESSAGE) + "...";
    }

    static String normalizeBaseUrl(String instance) {
        String value = instance.trim();
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            // The cause is dropped on purpose: its message repeats the raw value, which may contain credentials.
            throw new IllegalArgumentException("Invalid instance, expected a hostname or https:// base URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/api/v6")) {
            path = "";
        }
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !path.isEmpty()) {
            // The raw value is left out of the message: it may contain credentials.
            throw new IllegalArgumentException("Invalid instance, expected a hostname or https:// base URL");
        }
        if (scheme.equals("http") && !isLoopback(uri.getHost())) {
            // Plain HTTP would send the access token in cleartext; only allow it for local test servers.
            throw new IllegalArgumentException("Instance must use https:// (http:// is allowed for localhost only)");
        }
        return scheme + "://" + uri.getRawAuthority();
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase();
        return h.equals("localhost") || h.equals("[::1]") || h.matches("127(\\.\\d{1,3}){3}");
    }

    /**
     * Rejects tokens that cannot be sent in a header. The JDK would otherwise fail with an
     * exception whose message contains the token itself.
     */
    private static void validateToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException(
                        "accessToken contains whitespace or non-printable characters (check for a trailing newline)");
            }
        }
    }

    static ObjectMapper defaultObjectMapper() {
        JavaTimeModule timeModule = new JavaTimeModule();
        timeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMAT));
        timeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMAT));
        return new ObjectMapper()
                .registerModule(timeModule)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static String defaultUserAgent() {
        String version = DaktelaConnector.class.getPackage().getImplementationVersion();
        return "DaktelaJavaConnector/" + (version != null ? version : "dev");
    }

    /** Pauses between retries; replaceable in tests. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Builder for DaktelaConnector.
     */
    public static class Builder {
        private String instance;
        private String accessToken;
        private Duration timeout = Duration.ofSeconds(30);
        private AuthMethod authMethod = AuthMethod.HEADER;
        private String userAgent = defaultUserAgent();
        private int maxRetries = 0;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private Sleeper sleeper = duration -> Thread.sleep(duration.toMillis());

        private Builder() {
        }

        /**
         * Sets the Daktela instance. Accepts a hostname ({@code "my.daktela.com"}) or a base URL
         * ({@code "https://my.daktela.com"}); {@code https://} is assumed when no scheme is given.
         * Plain {@code http://} is accepted only for localhost, so the token is never sent in cleartext.
         *
         * @param instance the instance hostname or base URL
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
         * Sets the connect and request timeout.
         *
         * @param timeout the timeout duration, must be positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the authentication method.
         * <p>
         * Prefer a header-based method: with {@link AuthMethod#QUERY} the token becomes part of
         * every URL and can end up in proxy and server access logs.
         *
         * @param authMethod HEADER (default), BEARER or QUERY
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
         * Sets how many times a failed request is retried (default 0, no retries).
         * <p>
         * Rate-limited requests (HTTP 429) are retried for every method, honouring
         * {@code Retry-After}. Network errors and HTTP 502/503/504 are retried for GET only,
         * because other methods may already have taken effect. Delays grow exponentially from 500 ms.
         *
         * @param maxRetries number of retries, must not be negative
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Uses a custom {@link HttpClient}, for example to configure a proxy or TLS settings.
         * The connector's timeout is still applied per request.
         *
         * @param httpClient the HTTP client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Uses a custom Jackson {@link ObjectMapper} for request bodies and
         * {@link DaktelaResponse#getDataAs(Class)} conversions. By default unknown properties are
         * ignored and {@code java.time} types use the API's {@code yyyy-MM-dd HH:mm:ss} format.
         *
         * @param objectMapper the object mapper
         * @return this builder
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        /**
         * Builds the connector.
         *
         * @return a new DaktelaConnector instance
         * @throws NullPointerException     if required fields are missing
         * @throws IllegalArgumentException if a setting is invalid
         */
        public DaktelaConnector build() {
            Objects.requireNonNull(instance, "instance is required");
            Objects.requireNonNull(accessToken, "accessToken is required");
            Objects.requireNonNull(timeout, "timeout is required");
            Objects.requireNonNull(authMethod, "authMethod is required");
            Objects.requireNonNull(userAgent, "userAgent is required");
            if (instance.isBlank()) {
                throw new IllegalArgumentException("instance must not be blank");
            }
            if (accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken must not be blank");
            }
            validateToken(accessToken);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            return new DaktelaConnector(this);
        }
    }
}
