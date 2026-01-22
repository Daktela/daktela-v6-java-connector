package com.daktela.connector.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    private DaktelaQuery(Builder builder) {
        this.fields = Collections.unmodifiableList(new ArrayList<>(builder.fields));
        this.filters = Collections.unmodifiableList(new ArrayList<>(builder.filters));
        this.sorts = Collections.unmodifiableList(new ArrayList<>(builder.sorts));
        this.take = builder.take;
        this.skip = builder.skip;
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
     * Returns the list of fields to retrieve.
     *
     * @return unmodifiable list of field names
     */
    public List<String> getFields() {
        return fields;
    }

    /**
     * Returns the list of filters.
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
     * Builder for DaktelaQuery.
     */
    public static class Builder {
        private final List<String> fields = new ArrayList<>();
        private final List<DaktelaFilter> filters = new ArrayList<>();
        private final List<DaktelaSort> sorts = new ArrayList<>();
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
            this.fields.addAll(Arrays.asList(fieldNames));
            return this;
        }

        /**
         * Adds fields to retrieve.
         *
         * @param fieldNames the field names
         * @return this builder
         */
        public Builder fields(List<String> fieldNames) {
            this.fields.addAll(fieldNames);
            return this;
        }

        /**
         * Adds a filter.
         *
         * @param filter the filter to add
         * @return this builder
         */
        public Builder filter(DaktelaFilter filter) {
            this.filters.add(filter);
            return this;
        }

        /**
         * Adds multiple filters.
         *
         * @param filters the filters to add
         * @return this builder
         */
        public Builder filters(DaktelaFilter... filters) {
            this.filters.addAll(Arrays.asList(filters));
            return this;
        }

        /**
         * Adds multiple filters.
         *
         * @param filters the filters to add
         * @return this builder
         */
        public Builder filters(List<DaktelaFilter> filters) {
            this.filters.addAll(filters);
            return this;
        }

        /**
         * Adds a sort.
         *
         * @param sort the sort to add
         * @return this builder
         */
        public Builder sort(DaktelaSort sort) {
            this.sorts.add(sort);
            return this;
        }

        /**
         * Adds multiple sorts.
         *
         * @param sorts the sorts to add
         * @return this builder
         */
        public Builder sorts(DaktelaSort... sorts) {
            this.sorts.addAll(Arrays.asList(sorts));
            return this;
        }

        /**
         * Adds multiple sorts.
         *
         * @param sorts the sorts to add
         * @return this builder
         */
        public Builder sorts(List<DaktelaSort> sorts) {
            this.sorts.addAll(sorts);
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
            this.take = take;
            this.skip = skip;
            return this;
        }

        /**
         * Sets the take (limit) value.
         *
         * @param take number of records to retrieve
         * @return this builder
         */
        public Builder take(int take) {
            this.take = take;
            return this;
        }

        /**
         * Sets the skip (offset) value.
         *
         * @param skip number of records to skip
         * @return this builder
         */
        public Builder skip(int skip) {
            this.skip = skip;
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
