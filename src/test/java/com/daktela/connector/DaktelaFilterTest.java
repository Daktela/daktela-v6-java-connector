package com.daktela.connector;

import com.daktela.connector.query.DaktelaFilter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DaktelaFilterTest {

    @Test
    void testEqFilter() {
        DaktelaFilter filter = DaktelaFilter.eq("stage", "OPEN");

        assertEquals("stage", filter.getField());
        assertEquals("eq", filter.getOperator());
        assertEquals("OPEN", filter.getValue());
        assertFalse(filter.isOr());
    }

    @Test
    void testNeqFilter() {
        DaktelaFilter filter = DaktelaFilter.neq("status", "CLOSED");

        assertEquals("status", filter.getField());
        assertEquals("neq", filter.getOperator());
        assertEquals("CLOSED", filter.getValue());
    }

    @Test
    void testGtFilter() {
        DaktelaFilter filter = DaktelaFilter.gt("priority", 5);

        assertEquals("priority", filter.getField());
        assertEquals("gt", filter.getOperator());
        assertEquals(5, filter.getValue());
    }

    @Test
    void testGteFilter() {
        DaktelaFilter filter = DaktelaFilter.gte("created", "2024-01-01");

        assertEquals("created", filter.getField());
        assertEquals("gte", filter.getOperator());
        assertEquals("2024-01-01", filter.getValue());
    }

    @Test
    void testLtFilter() {
        DaktelaFilter filter = DaktelaFilter.lt("count", 10);

        assertEquals("count", filter.getField());
        assertEquals("lt", filter.getOperator());
        assertEquals(10, filter.getValue());
    }

    @Test
    void testLteFilter() {
        DaktelaFilter filter = DaktelaFilter.lte("edited", "2024-12-31");

        assertEquals("edited", filter.getField());
        assertEquals("lte", filter.getOperator());
        assertEquals("2024-12-31", filter.getValue());
    }

    @Test
    void testLikeFilter() {
        DaktelaFilter filter = DaktelaFilter.like("title", "urgent");

        assertEquals("title", filter.getField());
        assertEquals("like", filter.getOperator());
        assertEquals("urgent", filter.getValue());
    }

    @Test
    void testInFilterWithCollection() {
        List<String> values = Arrays.asList("OPEN", "NEW", "PENDING");
        DaktelaFilter filter = DaktelaFilter.in("stage", values);

        assertEquals("stage", filter.getField());
        assertEquals("in", filter.getOperator());
        assertEquals(values, filter.getValue());
    }

    @Test
    void testInFilterWithVarargs() {
        DaktelaFilter filter = DaktelaFilter.in("stage", "OPEN", "NEW");

        assertEquals("stage", filter.getField());
        assertEquals("in", filter.getOperator());
        assertEquals(Arrays.asList("OPEN", "NEW"), filter.getValue());
    }

    @Test
    void testNotInFilterWithCollection() {
        List<String> values = Arrays.asList("CLOSED", "ARCHIVED");
        DaktelaFilter filter = DaktelaFilter.notIn("stage", values);

        assertEquals("stage", filter.getField());
        assertEquals("notin", filter.getOperator());
        assertEquals(values, filter.getValue());
    }

    @Test
    void testNotInFilterWithVarargs() {
        DaktelaFilter filter = DaktelaFilter.notIn("stage", "CLOSED", "ARCHIVED");

        assertEquals("stage", filter.getField());
        assertEquals("notin", filter.getOperator());
        assertEquals(Arrays.asList("CLOSED", "ARCHIVED"), filter.getValue());
    }

    @Test
    void testOrFilter() {
        DaktelaFilter filter1 = DaktelaFilter.eq("stage", "OPEN");
        DaktelaFilter filter2 = DaktelaFilter.eq("stage", "NEW");
        DaktelaFilter orFilter = DaktelaFilter.or(filter1, filter2);

        assertTrue(orFilter.isOr());
        assertNull(orFilter.getField());
        assertNull(orFilter.getOperator());
        assertNull(orFilter.getValue());
        assertNotNull(orFilter.getFilters());
        assertEquals(2, orFilter.getFilters().size());
    }

    @Test
    void testOrFilterWithList() {
        List<DaktelaFilter> filters = Arrays.asList(
                DaktelaFilter.eq("priority", 1),
                DaktelaFilter.eq("priority", 2),
                DaktelaFilter.eq("priority", 3)
        );
        DaktelaFilter orFilter = DaktelaFilter.or(filters);

        assertTrue(orFilter.isOr());
        assertEquals(3, orFilter.getFilters().size());
    }

    @Test
    void testToMap() {
        DaktelaFilter filter = DaktelaFilter.eq("stage", "OPEN");
        Map<String, Object> map = filter.toMap();

        assertEquals("stage", map.get("field"));
        assertEquals("eq", map.get("operator"));
        assertEquals("OPEN", map.get("value"));
    }

    @Test
    void testOrFilterToMap() {
        DaktelaFilter orFilter = DaktelaFilter.or(
                DaktelaFilter.eq("stage", "OPEN"),
                DaktelaFilter.eq("stage", "NEW")
        );
        Map<String, Object> map = orFilter.toMap();

        assertEquals("or", map.get("logic"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) map.get("filters");
        assertEquals(2, filters.size());
        assertEquals("stage", filters.get(0).get("field"));
        assertEquals("eq", filters.get(0).get("operator"));
        assertEquals("OPEN", filters.get(0).get("value"));
    }

    @Test
    void testValueLessOperatorsOmitValue() {
        assertEquals(Map.of("field", "user", "operator", "isnull"), DaktelaFilter.isNull("user").toMap());
        assertEquals("isnotnull", DaktelaFilter.isNotNull("user").getOperator());
    }

    @Test
    void testStringMatchOperators() {
        assertEquals("contains", DaktelaFilter.contains("title", "x").getOperator());
        assertEquals("doesnotcontain", DaktelaFilter.doesNotContain("title", "x").getOperator());
        assertEquals("startswith", DaktelaFilter.startsWith("title", "x").getOperator());
        assertEquals("endswith", DaktelaFilter.endsWith("title", "x").getOperator());
        assertEquals("notlike", DaktelaFilter.notLike("title", "%x%").getOperator());
    }

    @Test
    void testBetween() {
        DaktelaFilter filter = DaktelaFilter.between("created", "2024-01-01", "2024-12-31");

        assertEquals("between", filter.getOperator());
        assertEquals(Arrays.asList("2024-01-01", "2024-12-31"), filter.getValue());
    }

    @Test
    void testCustomOperatorCopiesCollections() {
        List<String> values = new java.util.ArrayList<>(List.of("a"));
        DaktelaFilter filter = DaktelaFilter.of("name", "in", values);
        values.add("b");

        assertEquals(List.of("a"), filter.getValue());
    }

    @Test
    void testNullFieldIsRejected() {
        assertThrows(NullPointerException.class, () -> DaktelaFilter.eq(null, "x"));
    }

    @Test
    void testEmptyGroupIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DaktelaFilter.or());
    }

    @Test
    void testInFilterIsDefensivelyCopied() {
        Object[] values = {"a", "b"};
        DaktelaFilter filter = DaktelaFilter.in("stage", values);
        values[0] = "changed";

        assertEquals(Arrays.asList("a", "b"), filter.getValue());
    }
}
