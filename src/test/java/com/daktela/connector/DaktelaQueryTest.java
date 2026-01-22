package com.daktela.connector;

import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DaktelaQueryTest {

    @Test
    void testEmptyQuery() {
        DaktelaQuery query = DaktelaQuery.builder().build();

        assertTrue(query.getFields().isEmpty());
        assertTrue(query.getFilters().isEmpty());
        assertTrue(query.getSorts().isEmpty());
        assertNull(query.getTake());
        assertNull(query.getSkip());
    }

    @Test
    void testFieldsWithVarargs() {
        DaktelaQuery query = DaktelaQuery.builder()
                .fields("name", "title", "category")
                .build();

        assertEquals(3, query.getFields().size());
        assertEquals("name", query.getFields().get(0));
        assertEquals("title", query.getFields().get(1));
        assertEquals("category", query.getFields().get(2));
    }

    @Test
    void testFieldsWithList() {
        DaktelaQuery query = DaktelaQuery.builder()
                .fields(Arrays.asList("name", "title"))
                .build();

        assertEquals(2, query.getFields().size());
    }

    @Test
    void testSingleFilter() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .build();

        assertEquals(1, query.getFilters().size());
        assertEquals("stage", query.getFilters().get(0).getField());
    }

    @Test
    void testMultipleFilters() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .filter(DaktelaFilter.gte("created", "2024-01-01"))
                .build();

        assertEquals(2, query.getFilters().size());
    }

    @Test
    void testFiltersWithVarargs() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filters(
                        DaktelaFilter.eq("stage", "OPEN"),
                        DaktelaFilter.gte("priority", 5)
                )
                .build();

        assertEquals(2, query.getFilters().size());
    }

    @Test
    void testFiltersWithList() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filters(Arrays.asList(
                        DaktelaFilter.eq("stage", "OPEN"),
                        DaktelaFilter.gte("priority", 5)
                ))
                .build();

        assertEquals(2, query.getFilters().size());
    }

    @Test
    void testSingleSort() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sort(DaktelaSort.desc("created"))
                .build();

        assertEquals(1, query.getSorts().size());
        assertEquals("created", query.getSorts().get(0).getField());
        assertEquals("desc", query.getSorts().get(0).getDirection());
    }

    @Test
    void testMultipleSorts() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sort(DaktelaSort.desc("priority"))
                .sort(DaktelaSort.asc("name"))
                .build();

        assertEquals(2, query.getSorts().size());
    }

    @Test
    void testSortsWithVarargs() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sorts(
                        DaktelaSort.desc("priority"),
                        DaktelaSort.asc("name")
                )
                .build();

        assertEquals(2, query.getSorts().size());
    }

    @Test
    void testSortsWithList() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sorts(Arrays.asList(
                        DaktelaSort.desc("priority"),
                        DaktelaSort.asc("name")
                ))
                .build();

        assertEquals(2, query.getSorts().size());
    }

    @Test
    void testPagination() {
        DaktelaQuery query = DaktelaQuery.builder()
                .pagination(10, 20)
                .build();

        assertEquals(10, query.getTake());
        assertEquals(20, query.getSkip());
    }

    @Test
    void testTakeOnly() {
        DaktelaQuery query = DaktelaQuery.builder()
                .take(50)
                .build();

        assertEquals(50, query.getTake());
        assertNull(query.getSkip());
    }

    @Test
    void testSkipOnly() {
        DaktelaQuery query = DaktelaQuery.builder()
                .skip(100)
                .build();

        assertNull(query.getTake());
        assertEquals(100, query.getSkip());
    }

    @Test
    void testCompleteQuery() {
        DaktelaQuery query = DaktelaQuery.builder()
                .fields("name", "title", "category")
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .filter(DaktelaFilter.gte("created", "2024-01-01"))
                .sort(DaktelaSort.desc("edited"))
                .pagination(10, 0)
                .build();

        assertEquals(3, query.getFields().size());
        assertEquals(2, query.getFilters().size());
        assertEquals(1, query.getSorts().size());
        assertEquals(10, query.getTake());
        assertEquals(0, query.getSkip());
    }

    @Test
    void testFieldsAreImmutable() {
        DaktelaQuery query = DaktelaQuery.builder()
                .fields("name")
                .build();

        assertThrows(UnsupportedOperationException.class, () -> query.getFields().add("test"));
    }

    @Test
    void testFiltersAreImmutable() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                query.getFilters().add(DaktelaFilter.eq("test", "value")));
    }

    @Test
    void testSortsAreImmutable() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sort(DaktelaSort.asc("name"))
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                query.getSorts().add(DaktelaSort.desc("test")));
    }
}
