package com.daktela.connector;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable response wrapper for Daktela API responses.
 */
public class DaktelaResponse {

    private final int status;
    private final Object data;
    private final Integer total;
    private final List<Object> errors;
    private final ObjectMapper objectMapper;

    DaktelaResponse(int status, Object data, Integer total, List<Object> errors, ObjectMapper objectMapper) {
        this.status = status;
        this.data = data;
        this.total = total;
        this.errors = errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the raw data from the response.
     *
     * @return data object (Map or List)
     */
    public Object getData() {
        return data;
    }

    /**
     * Converts the data to the specified type using Jackson.
     *
     * @param type the target class
     * @param <T>  the target type
     * @return the converted data
     * @throws IllegalArgumentException if conversion fails
     */
    public <T> T getDataAs(Class<T> type) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(data, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to convert data to " + type.getName(), e);
        }
    }

    /**
     * Returns the data as a list of maps.
     *
     * @return list of maps, or empty list if data is not a list
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDataAsList() {
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        return Collections.emptyList();
    }

    /**
     * Returns the data as a map.
     *
     * @return map, or empty map if data is not a map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return Collections.emptyMap();
    }

    /**
     * Returns the total count of records (for paginated responses).
     *
     * @return total count, or 0 if not available
     */
    public int getTotal() {
        return total != null ? total : 0;
    }

    /**
     * Returns whether the request was successful (2xx status).
     *
     * @return true if status is 2xx
     */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /**
     * Returns whether the response contains errors.
     *
     * @return true if there are errors
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Returns the list of errors from the response.
     *
     * @return unmodifiable list of errors
     */
    public List<Object> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "DaktelaResponse{" +
                "status=" + status +
                ", data=" + data +
                ", total=" + total +
                ", errors=" + errors +
                '}';
    }
}
