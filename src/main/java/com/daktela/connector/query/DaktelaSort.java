package com.daktela.connector.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for Daktela API sort expressions.
 * <p>
 * Example usage:
 * <pre>{@code
 * DaktelaSort.asc("name")
 * DaktelaSort.desc("created")
 * }</pre>
 */
public class DaktelaSort {

    private final String field;
    private final String direction;

    private DaktelaSort(String field, String direction) {
        this.field = Objects.requireNonNull(field, "field is required");
        this.direction = direction;
    }

    /**
     * Creates an ascending sort.
     *
     * @param field the field to sort by
     * @return a new sort instance
     */
    public static DaktelaSort asc(String field) {
        return new DaktelaSort(field, "asc");
    }

    /**
     * Creates a descending sort.
     *
     * @param field the field to sort by
     * @return a new sort instance
     */
    public static DaktelaSort desc(String field) {
        return new DaktelaSort(field, "desc");
    }

    /**
     * Returns the field name.
     *
     * @return the field name
     */
    public String getField() {
        return field;
    }

    /**
     * Returns the sort direction.
     *
     * @return "asc" or "desc"
     */
    public String getDirection() {
        return direction;
    }

    /**
     * Converts this sort to a map representation for API serialization.
     *
     * @return map representation of the sort
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("dir", direction);
        return map;
    }

    @Override
    public String toString() {
        return field + " " + direction;
    }
}
