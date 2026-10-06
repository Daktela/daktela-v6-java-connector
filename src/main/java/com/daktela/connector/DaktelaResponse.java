package com.daktela.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable response wrapper for Daktela API responses.
 * <p>
 * For list endpoints {@link #getData()} holds the list of records and {@link #getTotal()} the
 * total number of matching records. For single-record reads, creates and updates it holds the
 * record itself.
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
     * @return data object (Map or List), or null if the response had no data
     */
    public Object getData() {
        return data;
    }

    /**
     * Converts the data to the specified type using Jackson. Unknown properties are ignored.
     *
     * @param type the target class
     * @param <T>  the target type
     * @return the converted data, or null if there is no data
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
     * Converts the data to a generic type using Jackson, e.g.
     * {@code getDataAs(new TypeReference<List<Ticket>>() {})}.
     *
     * @param type the target type reference
     * @param <T>  the target type
     * @return the converted data, or null if there is no data
     * @throws IllegalArgumentException if conversion fails
     */
    public <T> T getDataAs(TypeReference<T> type) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(data, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to convert data to " + type.getType().getTypeName(), e);
        }
    }

    /**
     * Converts list data to a list of the specified element type.
     *
     * @param elementType the element class
     * @param <T>         the element type
     * @return the converted list, or an empty list if the data is not a list
     * @throws IllegalArgumentException if conversion fails
     */
    public <T> List<T> getDataAsListOf(Class<T> elementType) {
        if (!(data instanceof List)) {
            return Collections.emptyList();
        }
        CollectionType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
        try {
            return objectMapper.convertValue(data, listType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to convert data to List<" + elementType.getName() + ">", e);
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
     * @return total count, or 0 if not available; use {@link #hasTotal()} to tell the difference
     */
    public int getTotal() {
        return total != null ? total : 0;
    }

    /**
     * Returns whether the API reported a total count.
     *
     * @return true if {@link #getTotal()} is a real value
     */
    public boolean hasTotal() {
        return total != null;
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
     * Returns whether the response contains errors. A successful HTTP status can still carry
     * application-level errors.
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
        // Record data is deliberately omitted: it often contains personal data that must not end up in logs.
        String dataSummary;
        if (data instanceof List) {
            dataSummary = "list(" + ((List<?>) data).size() + ")";
        } else if (data instanceof Map) {
            dataSummary = "record";
        } else {
            dataSummary = data == null ? "none" : data.getClass().getSimpleName();
        }
        return "DaktelaResponse{" +
                "status=" + status +
                ", data=" + dataSummary +
                ", total=" + total +
                ", errors=" + errors +
                '}';
    }
}
