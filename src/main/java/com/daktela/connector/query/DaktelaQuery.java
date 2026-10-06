package com.daktela.connector.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Combined query builder for Daktela API requests.
 * <p>
 * Example usage:
 * <pre>{@code
 * DaktelaQuery query = DaktelaQuery.builder()
 *     .fields("name", "title", "category")
 *     .filter(DaktelaFilter.eq("stage", "OPEN"))
 *     .filter(DaktelaFilter.gte("created", "2024-01-01"))
 *     .sort(DaktelaSort.desc("edited"))
 *     .pagination(10, 0)
 *     .build();
 * }</pre>
 */
public class DaktelaQuery {

    private final List<String> fields;
    private final List<DaktelaFilter> filters;
    private final List<DaktelaSort> sorts;
    private final Integer take;
    private final Integer skip;
    private final Map<String, String> params;

    private DaktelaQuery(Builder builder) {
        this.fields = Collections.unmodifiableList(new ArrayList<>(builder.fields));
        this.filters = Collections.unmodifiableList(new ArrayList<>(builder.filters));
        this.sorts = Collections.unmodifiableList(new ArrayList<>(builder.sorts));
        this.take = builder.take;
        this.skip = builder.skip;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(builder.params));
    }

    /**
     * Creates a new query builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-populated with this query's settings.
     *
     * @return a new builder instance
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.fields.addAll(fields);
        builder.filters.addAll(filters);
        builder.sorts.addAll(sorts);
        builder.take = take;
        builder.skip = skip;
        builder.params.putAll(params);
        return builder;
    }

    /**
     * Returns the list of fields to retrieve.
     *
     * @return unmodifiable list of field names
     */
    public List<String> getFields() {
        return fields;
    }

    /**
     * Returns the list of filters. Top-level filters are combined with AND.
     *
     * @return unmodifiable list of filters
     */
    public List<DaktelaFilter> getFilters() {
        return filters;
    }

    /**
     * Returns the list of sorts.
     *
     * @return unmodifiable list of sorts
     */
    public List<DaktelaSort> getSorts() {
        return sorts;
    }

    /**
     * Returns the take (limit) value.
     *
     * @return take value, or null if not set
     */
    public Integer getTake() {
        return take;
    }

    /**
     * Returns the skip (offset) value.
     *
     * @return skip value, or null if not set
     */
    public Integer getSkip() {
        return skip;
    }

    /**
     * Returns additional raw query parameters.
     *
     * @return unmodifiable map of parameter names to values
     */
    public Map<String, String> getParams() {
        return params;
    }

    /**
     * Builder for DaktelaQuery.
     */
    public static class Builder {
        private final List<String> fields = new ArrayList<>();
        private final List<DaktelaFilter> filters = new ArrayList<>();
        private final List<DaktelaSort> sorts = new ArrayList<>();
        private final Map<String, String> params = new LinkedHashMap<>();
        private Integer take;
        private Integer skip;

        private Builder() {
        }

        /**
         * Adds fields to retrieve.
         *
         * @param fieldNames the field names
         * @return this builder
         */
        public Builder fields(String... fieldNames) {
            return fields(Arrays.asList(fieldNames));
        }

        /**
         * Adds fields to retrieve.
         *
         * @param fieldNames the field names
         * @return this builder
         */
        public Builder fields(List<String> fieldNames) {
            for (String fieldName : fieldNames) {
                this.fields.add(Objects.requireNonNull(fieldName, "field name must not be null"));
            }
            return this;
        }

        /**
         * Adds a filter. Top-level filters are combined with AND.
         *
         * @param filter the filter to add
         * @return this builder
         */
        public Builder filter(DaktelaFilter filter) {
            this.filters.add(Objects.requireNonNull(filter, "filter must not be null"));
            return this;
        }

        /**
         * Adds multiple filters.
         *
         * @param filters the filters to add
         * @return this builder
         */
        public Builder filters(DaktelaFilter... filters) {
            return filters(Arrays.asList(filters));
        }

        /**
         * Adds multiple filters.
         *
         * @param filters the filters to add
         * @return this builder
         */
        public Builder filters(List<DaktelaFilter> filters) {
            for (DaktelaFilter filter : filters) {
                filter(filter);
            }
            return this;
        }

        /**
         * Adds a sort.
         *
         * @param sort the sort to add
         * @return this builder
         */
        public Builder sort(DaktelaSort sort) {
            this.sorts.add(Objects.requireNonNull(sort, "sort must not be null"));
            return this;
        }

        /**
         * Adds multiple sorts.
         *
         * @param sorts the sorts to add
         * @return this builder
         */
        public Builder sorts(DaktelaSort... sorts) {
            return sorts(Arrays.asList(sorts));
        }

        /**
         * Adds multiple sorts.
         *
         * @param sorts the sorts to add
         * @return this builder
         */
        public Builder sorts(List<DaktelaSort> sorts) {
            for (DaktelaSort sort : sorts) {
                sort(sort);
            }
            return this;
        }

        /**
         * Sets pagination parameters.
         *
         * @param take number of records to retrieve
         * @param skip number of records to skip
         * @return this builder
         */
        public Builder pagination(int take, int skip) {
            return take(take).skip(skip);
        }

        /**
         * Sets the take (limit) value.
         *
         * @param take number of records to retrieve, must be positive
         * @return this builder
         */
        public Builder take(int take) {
            if (take <= 0) {
                throw new IllegalArgumentException("take must be positive");
            }
            this.take = take;
            return this;
        }

        /**
         * Sets the skip (offset) value.
         *
         * @param skip number of records to skip, must not be negative
         * @return this builder
         */
        public Builder skip(int skip) {
            if (skip < 0) {
                throw new IllegalArgumentException("skip must not be negative");
            }
            this.skip = skip;
            return this;
        }

        /**
         * Adds a raw query parameter, for API options not covered by this builder.
         * The name and value are URL-encoded when the request is sent.
         *
         * @param name  the parameter name
         * @param value the parameter value
         * @return this builder
         */
        public Builder param(String name, String value) {
            this.params.put(Objects.requireNonNull(name, "name must not be null"),
                    Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        /**
         * Builds the query.
         *
         * @return a new DaktelaQuery instance
         */
        public DaktelaQuery build() {
            return new DaktelaQuery(this);
        }
    }
}
