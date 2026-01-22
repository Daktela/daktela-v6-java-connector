package com.daktela.connector.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for Daktela API filter expressions.
 * <p>
 * Example usage:
 * <pre>{@code
 * DaktelaFilter.eq("stage", "OPEN")
 * DaktelaFilter.gte("created", "2024-01-01")
 * DaktelaFilter.in("status", Arrays.asList("NEW", "OPEN"))
 * DaktelaFilter.or(
 *     DaktelaFilter.eq("stage", "OPEN"),
 *     DaktelaFilter.eq("stage", "NEW")
 * )
 * }</pre>
 */
public class DaktelaFilter {

    private final String field;
    private final String operator;
    private final Object value;
    private final List<DaktelaFilter> orFilters;
    private final boolean isOr;

    private DaktelaFilter(String field, String operator, Object value) {
        this.field = field;
        this.operator = operator;
        this.value = value;
        this.orFilters = null;
        this.isOr = false;
    }

    private DaktelaFilter(List<DaktelaFilter> orFilters) {
        this.field = null;
        this.operator = null;
        this.value = null;
        this.orFilters = Collections.unmodifiableList(new ArrayList<>(orFilters));
        this.isOr = true;
    }

    /**
     * Creates an equals filter (field = value).
     *
     * @param field the field name
     * @param value the value to match
     * @return a new filter instance
     */
    public static DaktelaFilter eq(String field, Object value) {
        return new DaktelaFilter(field, "eq", value);
    }

    /**
     * Creates a not equals filter (field != value).
     *
     * @param field the field name
     * @param value the value to exclude
     * @return a new filter instance
     */
    public static DaktelaFilter neq(String field, Object value) {
        return new DaktelaFilter(field, "neq", value);
    }

    /**
     * Creates a greater than filter (field &gt; value).
     *
     * @param field the field name
     * @param value the value to compare
     * @return a new filter instance
     */
    public static DaktelaFilter gt(String field, Object value) {
        return new DaktelaFilter(field, "gt", value);
    }

    /**
     * Creates a greater than or equal filter (field &gt;= value).
     *
     * @param field the field name
     * @param value the value to compare
     * @return a new filter instance
     */
    public static DaktelaFilter gte(String field, Object value) {
        return new DaktelaFilter(field, "gte", value);
    }

    /**
     * Creates a less than filter (field &lt; value).
     *
     * @param field the field name
     * @param value the value to compare
     * @return a new filter instance
     */
    public static DaktelaFilter lt(String field, Object value) {
        return new DaktelaFilter(field, "lt", value);
    }

    /**
     * Creates a less than or equal filter (field &lt;= value).
     *
     * @param field the field name
     * @param value the value to compare
     * @return a new filter instance
     */
    public static DaktelaFilter lte(String field, Object value) {
        return new DaktelaFilter(field, "lte", value);
    }

    /**
     * Creates a like filter (field contains value).
     *
     * @param field the field name
     * @param value the value to search for
     * @return a new filter instance
     */
    public static DaktelaFilter like(String field, Object value) {
        return new DaktelaFilter(field, "like", value);
    }

    /**
     * Creates an in filter (field in values).
     *
     * @param field  the field name
     * @param values the values to match
     * @return a new filter instance
     */
    public static DaktelaFilter in(String field, Collection<?> values) {
        return new DaktelaFilter(field, "in", new ArrayList<>(values));
    }

    /**
     * Creates an in filter (field in values).
     *
     * @param field  the field name
     * @param values the values to match
     * @return a new filter instance
     */
    public static DaktelaFilter in(String field, Object... values) {
        return new DaktelaFilter(field, "in", Arrays.asList(values));
    }

    /**
     * Creates a not in filter (field not in values).
     *
     * @param field  the field name
     * @param values the values to exclude
     * @return a new filter instance
     */
    public static DaktelaFilter notIn(String field, Collection<?> values) {
        return new DaktelaFilter(field, "nin", new ArrayList<>(values));
    }

    /**
     * Creates a not in filter (field not in values).
     *
     * @param field  the field name
     * @param values the values to exclude
     * @return a new filter instance
     */
    public static DaktelaFilter notIn(String field, Object... values) {
        return new DaktelaFilter(field, "nin", Arrays.asList(values));
    }

    /**
     * Creates an OR combination of filters.
     *
     * @param filters the filters to combine with OR
     * @return a new filter instance representing the OR combination
     */
    public static DaktelaFilter or(DaktelaFilter... filters) {
        return new DaktelaFilter(Arrays.asList(filters));
    }

    /**
     * Creates an OR combination of filters.
     *
     * @param filters the filters to combine with OR
     * @return a new filter instance representing the OR combination
     */
    public static DaktelaFilter or(List<DaktelaFilter> filters) {
        return new DaktelaFilter(filters);
    }

    /**
     * Returns whether this is an OR filter combination.
     *
     * @return true if this is an OR filter
     */
    public boolean isOr() {
        return isOr;
    }

    /**
     * Returns the field name.
     *
     * @return the field name, or null for OR filters
     */
    public String getField() {
        return field;
    }

    /**
     * Returns the operator.
     *
     * @return the operator, or null for OR filters
     */
    public String getOperator() {
        return operator;
    }

    /**
     * Returns the filter value.
     *
     * @return the value, or null for OR filters
     */
    public Object getValue() {
        return value;
    }

    /**
     * Returns the OR filters.
     *
     * @return list of OR filters, or null for simple filters
     */
    public List<DaktelaFilter> getOrFilters() {
        return orFilters;
    }

    /**
     * Converts this filter to a map representation for API serialization.
     *
     * @return map representation of the filter
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (isOr && orFilters != null) {
            List<Map<String, Object>> orList = new ArrayList<>();
            for (DaktelaFilter f : orFilters) {
                orList.add(f.toMap());
            }
            map.put("or", orList);
        } else {
            map.put("field", field);
            map.put("operator", operator);
            map.put("value", value);
        }
        return map;
    }
}
