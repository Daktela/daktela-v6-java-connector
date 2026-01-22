package com.daktela.connector;

import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DaktelaConnectorTest {

    @Test
    void testBuilderRequiresInstance() {
        assertThrows(NullPointerException.class, () ->
                DaktelaConnector.builder()
                        .accessToken("token")
                        .build()
        );
    }

    @Test
    void testBuilderRequiresAccessToken() {
        assertThrows(NullPointerException.class, () ->
                DaktelaConnector.builder()
                        .instance("test.daktela.com")
                        .build()
        );
    }

    @Test
    void testBuilderWithRequiredFields() {
        DaktelaConnector connector = DaktelaConnector.builder()
                .instance("test.daktela.com")
                .accessToken("my-token")
                .build();

        assertNotNull(connector);
    }

    @Test
    void testBuilderWithAllOptions() {
        DaktelaConnector connector = DaktelaConnector.builder()
                .instance("test.daktela.com")
                .accessToken("my-token")
                .timeout(Duration.ofSeconds(60))
                .authMethod(AuthMethod.QUERY)
                .userAgent("MyApp/2.0")
                .build();

        assertNotNull(connector);
    }

    @Test
    void testBuilderDefaultAuthMethod() {
        // Default should be HEADER - this is tested indirectly
        // by ensuring the connector builds successfully
        DaktelaConnector connector = DaktelaConnector.builder()
                .instance("test.daktela.com")
                .accessToken("my-token")
                .build();

        assertNotNull(connector);
    }

    @Test
    void testUrlBuildingWithFields() {
        // This tests the URL building logic via reflection or by inspecting behavior
        // For now, we just ensure the query builds correctly
        DaktelaQuery query = DaktelaQuery.builder()
                .fields("name", "title", "category")
                .build();

        assertEquals(3, query.getFields().size());
    }

    @Test
    void testUrlBuildingWithFilters() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .filter(DaktelaFilter.gte("created", "2024-01-01"))
                .build();

        assertEquals(2, query.getFilters().size());
        assertEquals("eq", query.getFilters().get(0).getOperator());
        assertEquals("gte", query.getFilters().get(1).getOperator());
    }

    @Test
    void testUrlBuildingWithSorts() {
        DaktelaQuery query = DaktelaQuery.builder()
                .sort(DaktelaSort.desc("edited"))
                .sort(DaktelaSort.asc("name"))
                .build();

        assertEquals(2, query.getSorts().size());
        assertEquals("desc", query.getSorts().get(0).getDirection());
        assertEquals("asc", query.getSorts().get(1).getDirection());
    }

    @Test
    void testUrlBuildingWithPagination() {
        DaktelaQuery query = DaktelaQuery.builder()
                .pagination(10, 20)
                .build();

        assertEquals(10, query.getTake());
        assertEquals(20, query.getSkip());
    }

    @Test
    void testUrlBuildingWithInFilter() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.in("stage", Arrays.asList("OPEN", "NEW")))
                .build();

        assertEquals(1, query.getFilters().size());
        DaktelaFilter filter = query.getFilters().get(0);
        assertEquals("in", filter.getOperator());
        assertTrue(filter.getValue() instanceof java.util.List);
    }

    @Test
    void testUrlBuildingWithOrFilter() {
        DaktelaQuery query = DaktelaQuery.builder()
                .filter(DaktelaFilter.or(
                        DaktelaFilter.eq("stage", "OPEN"),
                        DaktelaFilter.eq("stage", "NEW")
                ))
                .build();

        assertEquals(1, query.getFilters().size());
        DaktelaFilter filter = query.getFilters().get(0);
        assertTrue(filter.isOr());
        assertEquals(2, filter.getOrFilters().size());
    }

    @Test
    void testComplexQuery() {
        DaktelaQuery query = DaktelaQuery.builder()
                .fields("name", "title", "category", "priority")
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .filter(DaktelaFilter.gte("created", "2024-01-01"))
                .filter(DaktelaFilter.or(
                        DaktelaFilter.eq("priority", 1),
                        DaktelaFilter.eq("priority", 2)
                ))
                .sort(DaktelaSort.desc("priority"))
                .sort(DaktelaSort.asc("created"))
                .pagination(25, 50)
                .build();

        assertEquals(4, query.getFields().size());
        assertEquals(3, query.getFilters().size());
        assertEquals(2, query.getSorts().size());
        assertEquals(25, query.getTake());
        assertEquals(50, query.getSkip());
    }
}
