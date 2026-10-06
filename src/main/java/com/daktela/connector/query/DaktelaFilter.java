package com.daktela.connector.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for Daktela API filter expressions.
 * <p>
 * Filters are serialized in the Kendo-style format the Daktela V6 API expects
 * ({@code filter[logic]=and&filter[filters][0][field]=...}). Groups created with
 * {@link #or(DaktelaFilter...)} or {@link #and(DaktelaFilter...)} may be nested.
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

    static final String LOGIC_AND = "and";
    static final String LOGIC_OR = "or";

    private final String field;
    private final String operator;
    private final Object value;
    private final String logic;
    private final List<DaktelaFilter> filters;

    private DaktelaFilter(String field, String operator, Object value) {
        this.field = Objects.requireNonNull(field, "field is required");
        this.operator = Objects.requireNonNull(operator, "operator is required");
        this.value = value;
        this.logic = null;
        this.filters = null;
    }

    private DaktelaFilter(String logic, List<DaktelaFilter> filters) {
        Objects.requireNonNull(filters, "filters are required");
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("a filter group needs at least one filter");
        }
        for (DaktelaFilter filter : filters) {
            Objects.requireNonNull(filter, "filters must not contain null");
        }
        this.field = null;
        this.operator = null;
        this.value = null;
        this.logic = logic;
        this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
    }

    /**
     * Creates a filter with an arbitrary operator. Use this for operators that have no
     * dedicated factory method.
     *
     * @param field    the field name
     * @param operator the operator as understood by the Daktela API
     * @param value    the value, or {@code null} for operators that take no value
     * @return a new filter instance
     */
    public static DaktelaFilter of(String field, String operator, Object value) {
        return new DaktelaFilter(field, operator, copyIfCollection(value));
    }

    /**
     * Creates an equals filter (field = value). A {@code null} value creates {@link #isNull(String)}.
     *
     * @param field the field name
     * @param value the value to match
     * @return a new filter instance
     */
    public static DaktelaFilter eq(String field, Object value) {
        return value == null ? isNull(field) : new DaktelaFilter(field, "eq", value);
    }

    /**
     * Creates a not equals filter (field != value). A {@code null} value creates
     * {@link #isNotNull(String)}.
     *
     * @param field the field name
     * @param value the value to exclude
     * @return a new filter instance
     */
    public static DaktelaFilter neq(String field, Object value) {
        return value == null ? isNotNull(field) : new DaktelaFilter(field, "neq", value);
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
     * Creates a SQL LIKE filter. The value is used as-is, so include the {@code %} wildcards
     * yourself (e.g. {@code like("title", "%urgent%")}); use {@link #contains(String, String)}
     * for a plain substring match.
     *
     * @param field the field name
     * @param value the LIKE pattern
     * @return a new filter instance
     */
    public static DaktelaFilter like(String field, Object value) {
        return new DaktelaFilter(field, "like", value);
    }

    /**
     * Creates a NOT LIKE filter. The value is used as-is, including {@code %} wildcards.
     *
     * @param field the field name
     * @param value the LIKE pattern
     * @return a new filter instance
     */
    public static DaktelaFilter notLike(String field, Object value) {
        return new DaktelaFilter(field, "notlike", value);
    }

    /**
     * Creates a substring filter (field contains value).
     *
     * @param field the field name
     * @param value the substring to search for
     * @return a new filter instance
     */
    public static DaktelaFilter contains(String field, String value) {
        return new DaktelaFilter(field, "contains", value);
    }

    /**
     * Creates a negated substring filter (field does not contain value).
     *
     * @param field the field name
     * @param value the substring to exclude
     * @return a new filter instance
     */
    public static DaktelaFilter doesNotContain(String field, String value) {
        return new DaktelaFilter(field, "doesnotcontain", value);
    }

    /**
     * Creates a prefix filter (field starts with value).
     *
     * @param field the field name
     * @param value the prefix
     * @return a new filter instance
     */
    public static DaktelaFilter startsWith(String field, String value) {
        return new DaktelaFilter(field, "startswith", value);
    }

    /**
     * Creates a suffix filter (field ends with value).
     *
     * @param field the field name
     * @param value the suffix
     * @return a new filter instance
     */
    public static DaktelaFilter endsWith(String field, String value) {
        return new DaktelaFilter(field, "endswith", value);
    }

    /**
     * Creates an inclusive range filter (from &lt;= field &lt;= to).
     *
     * @param field the field name
     * @param from  the lower bound
     * @param to    the upper bound
     * @return a new filter instance
     */
    public static DaktelaFilter between(String field, Object from, Object to) {
        return new DaktelaFilter(field, "between", new ArrayList<>(Arrays.asList(from, to)));
    }

    /**
     * Creates an in filter (field in values).
     *
     * @param field  the field name
     * @param values the values to match, must not be empty
     * @return a new filter instance
     * @throws IllegalArgumentException if values is empty
     */
    public static DaktelaFilter in(String field, Collection<?> values) {
        return new DaktelaFilter(field, "in", nonEmpty(values, "in"));
    }

    /**
     * Creates an in filter (field in values).
     *
     * @param field  the field name
     * @param values the values to match, must not be empty
     * @return a new filter instance
     * @throws IllegalArgumentException if values is empty
     */
    public static DaktelaFilter in(String field, Object... values) {
        return new DaktelaFilter(field, "in", nonEmpty(Arrays.asList(values), "in"));
    }

    /**
     * Creates a not in filter (field not in values).
     *
     * @param field  the field name
     * @param values the values to exclude, must not be empty
     * @return a new filter instance
     * @throws IllegalArgumentException if values is empty
     */
    public static DaktelaFilter notIn(String field, Collection<?> values) {
        return new DaktelaFilter(field, "notin", nonEmpty(values, "notIn"));
    }

    /**
     * Creates a not in filter (field not in values).
     *
     * @param field  the field name
     * @param values the values to exclude, must not be empty
     * @return a new filter instance
     * @throws IllegalArgumentException if values is empty
     */
    public static DaktelaFilter notIn(String field, Object... values) {
        return new DaktelaFilter(field, "notin", nonEmpty(Arrays.asList(values), "notIn"));
    }

    /**
     * Creates an is-null filter (field has no value).
     *
     * @param field the field name
     * @return a new filter instance
     */
    public static DaktelaFilter isNull(String field) {
        return new DaktelaFilter(field, "isnull", null);
    }

    /**
     * Creates an is-not-null filter (field has a value).
     *
     * @param field the field name
     * @return a new filter instance
     */
    public static DaktelaFilter isNotNull(String field) {
        return new DaktelaFilter(field, "isnotnull", null);
    }

    /**
     * Creates an OR group of filters.
     *
     * @param filters the filters to combine with OR
     * @return a new filter group
     */
    public static DaktelaFilter or(DaktelaFilter... filters) {
        return new DaktelaFilter(LOGIC_OR, Arrays.asList(filters));
    }

    /**
     * Creates an OR group of filters.
     *
     * @param filters the filters to combine with OR
     * @return a new filter group
     */
    public static DaktelaFilter or(List<DaktelaFilter> filters) {
        return new DaktelaFilter(LOGIC_OR, filters);
    }

    /**
     * Creates an AND group of filters. Useful for nesting inside an OR group;
     * top-level query filters are already combined with AND.
     *
     * @param filters the filters to combine with AND
     * @return a new filter group
     */
    public static DaktelaFilter and(DaktelaFilter... filters) {
        return new DaktelaFilter(LOGIC_AND, Arrays.asList(filters));
    }

    /**
     * Creates an AND group of filters.
     *
     * @param filters the filters to combine with AND
     * @return a new filter group
     */
    public static DaktelaFilter and(List<DaktelaFilter> filters) {
        return new DaktelaFilter(LOGIC_AND, filters);
    }

    /**
     * Returns whether this is a group of filters (created by {@code or(...)} or {@code and(...)}).
     *
     * @return true if this is a filter group
     */
    public boolean isGroup() {
        return logic != null;
    }

    /**
     * Returns whether this is an OR filter group.
     *
     * @return true if this is an OR group
     */
    public boolean isOr() {
        return LOGIC_OR.equals(logic);
    }

    /**
     * Returns the group logic.
     *
     * @return "and" or "or" for groups, null for simple filters
     */
    public String getLogic() {
        return logic;
    }

    /**
     * Returns the field name.
     *
     * @return the field name, or null for filter groups
     */
    public String getField() {
        return field;
    }

    /**
     * Returns the operator.
     *
     * @return the operator, or null for filter groups
     */
    public String getOperator() {
        return operator;
    }

    /**
     * Returns the filter value.
     *
     * @return the value, or null for filter groups and value-less operators
     */
    public Object getValue() {
        return value;
    }

    /**
     * Returns the filters of this group.
     *
     * @return unmodifiable list of filters, or null for simple filters
     */
    public List<DaktelaFilter> getFilters() {
        return filters;
    }

    /**
     * Returns the filters of an OR group.
     *
     * @return list of OR filters, or null if this is not an OR group
     * @deprecated use {@link #getFilters()} together with {@link #getLogic()}
     */
    @Deprecated
    public List<DaktelaFilter> getOrFilters() {
        return isOr() ? filters : null;
    }

    /**
     * Converts this filter to the Kendo-style map the Daktela API expects:
     * {@code {field, operator, value}} for simple filters and {@code {logic, filters}} for groups.
     *
     * @return map representation of the filter
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (isGroup()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (DaktelaFilter f : filters) {
                list.add(f.toMap());
            }
            map.put("logic", logic);
            map.put("filters", list);
        } else {
            map.put("field", field);
            map.put("operator", operator);
            if (value != null) {
                map.put("value", value);
            }
        }
        return map;
    }

    @Override
    public String toString() {
        return toMap().toString();
    }

    private static Object copyIfCollection(Object value) {
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        if (value instanceof Object[]) {
            return new ArrayList<>(Arrays.asList((Object[]) value));
        }
        return value;
    }

    private static List<Object> nonEmpty(Collection<?> values, String method) {
        if (values.isEmpty()) {
            // The API rejects an empty list; failing here gives a clearer error.
            throw new IllegalArgumentException(method + "() needs at least one value");
        }
        return new ArrayList<>(values);
    }
}
