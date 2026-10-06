import com.daktela.connector.AuthMethod;
import com.daktela.connector.DaktelaConnector;
import com.daktela.connector.DaktelaResponse;
import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaNotFoundException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Basic usage examples for Daktela V6 Java Connector.
 *
 * To run this example:
 * 1. Replace "your-instance.daktela.com" with your actual Daktela instance
 * 2. Replace "your-access-token" with a valid API access token
 * 3. Compile and run the example
 */
public class BasicUsageExample {

    public static void main(String[] args) {
        // Create connector with builder pattern
        DaktelaConnector connector = DaktelaConnector.builder()
                .instance("your-instance.daktela.com")
                .accessToken("your-access-token")
                .timeout(Duration.ofSeconds(30))
                .authMethod(AuthMethod.HEADER)  // or AuthMethod.QUERY
                .userAgent("MyApp/1.0")
                .build();

        try {
            // Example 1: Simple GET request
            simpleGet(connector);

            // Example 2: GET with query parameters
            getWithQuery(connector);

            // Example 3: GET with complex filters
            getWithComplexFilters(connector);

            // Example 4: POST - Create a new record
            createRecord(connector);

            // Example 5: PUT - Update a record
            updateRecord(connector);

            // Example 6: DELETE - Delete a record
            deleteRecord(connector);

        } catch (DaktelaUnauthorizedException e) {
            System.err.println("Authentication failed: " + e.getMessage());
        } catch (DaktelaNotFoundException e) {
            System.err.println("Resource not found: " + e.getMessage());
        } catch (DaktelaException e) {
            System.err.println("API error: " + e.getMessage() + " (status: " + e.getStatusCode() + ")");
        }
    }

    private static void simpleGet(DaktelaConnector connector) {
        System.out.println("=== Simple GET ===");

        // Get a single ticket by ID
        DaktelaResponse response = connector.get("tickets/123");

        if (response.isSuccess()) {
            Map<String, Object> ticket = response.getDataAsMap();
            System.out.println("Ticket: " + ticket);
        }
    }

    private static void getWithQuery(DaktelaConnector connector) {
        System.out.println("\n=== GET with Query ===");

        // Get tickets with filtering, sorting, and pagination
        DaktelaResponse response = connector.get("tickets",
                DaktelaQuery.builder()
                        .fields("name", "title", "category", "stage")
                        .filter(DaktelaFilter.eq("stage", "OPEN"))
                        .filter(DaktelaFilter.gte("created", "2024-01-01"))
                        .sort(DaktelaSort.desc("edited"))
                        .pagination(10, 0)  // take 10, skip 0
                        .build());

        if (response.isSuccess()) {
            List<Map<String, Object>> tickets = response.getDataAsList();
            System.out.println("Found " + response.getTotal() + " total tickets");
            System.out.println("Retrieved " + tickets.size() + " tickets");

            for (Map<String, Object> ticket : tickets) {
                System.out.println(" - " + ticket.get("title"));
            }
        }
    }

    private static void getWithComplexFilters(DaktelaConnector connector) {
        System.out.println("\n=== GET with Complex Filters ===");

        // Using IN filter
        DaktelaResponse response1 = connector.get("tickets",
                DaktelaQuery.builder()
                        .filter(DaktelaFilter.in("stage", "OPEN", "NEW", "PENDING"))
                        .build());
        System.out.println("Tickets in OPEN/NEW/PENDING: " + response1.getTotal());

        // Using OR filter
        DaktelaResponse response2 = connector.get("tickets",
                DaktelaQuery.builder()
                        .filter(DaktelaFilter.or(
                                DaktelaFilter.eq("priority", 1),
                                DaktelaFilter.eq("priority", 2)
                        ))
                        .build());
        System.out.println("High priority tickets: " + response2.getTotal());

        // Using NOT IN filter
        DaktelaResponse response3 = connector.get("tickets",
                DaktelaQuery.builder()
                        .filter(DaktelaFilter.notIn("stage", "CLOSED", "ARCHIVED"))
                        .build());
        System.out.println("Active tickets: " + response3.getTotal());

        // Using LIKE filter
        DaktelaResponse response4 = connector.get("tickets",
                DaktelaQuery.builder()
                        .filter(DaktelaFilter.contains("title", "urgent"))
                        .build());
        System.out.println("Urgent tickets: " + response4.getTotal());

        // Multiple filters (AND logic)
        DaktelaResponse response5 = connector.get("tickets",
                DaktelaQuery.builder()
                        .filter(DaktelaFilter.eq("stage", "OPEN"))
                        .filter(DaktelaFilter.gte("priority", 3))
                        .filter(DaktelaFilter.lte("created", "2024-06-01"))
                        .build());
        System.out.println("Filtered tickets: " + response5.getTotal());
    }

    private static void createRecord(DaktelaConnector connector) {
        System.out.println("\n=== POST - Create Record ===");

        DaktelaResponse response = connector.post("tickets", Map.of(
                "title", "New support ticket",
                "category", "support",
                "description", "This is a test ticket created via Java SDK"
        ));

        if (response.isSuccess()) {
            Map<String, Object> created = response.getDataAsMap();
            System.out.println("Created ticket with ID: " + created.get("name"));
        }
    }

    private static void updateRecord(DaktelaConnector connector) {
        System.out.println("\n=== PUT - Update Record ===");

        DaktelaResponse response = connector.put("tickets/123", Map.of(
                "title", "Updated ticket title",
                "stage", "IN_PROGRESS"
        ));

        if (response.isSuccess()) {
            System.out.println("Ticket updated successfully");
        }
    }

    private static void deleteRecord(DaktelaConnector connector) {
        System.out.println("\n=== DELETE - Delete Record ===");

        DaktelaResponse response = connector.delete("tickets/123");

        if (response.isSuccess()) {
            System.out.println("Ticket deleted successfully");
        }
    }
}
