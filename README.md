# Daktela V6 Java Connector

Java SDK for [Daktela V6 REST API](https://www.daktela.com/).

## Requirements

- Java 11 or higher
- Maven or Gradle

## Installation

### Maven

```xml
<dependency>
    <groupId>com.daktela</groupId>
    <artifactId>daktela-v6-java-connector</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.daktela:daktela-v6-java-connector:1.0.0'
```

## Quick Start

```java
import com.daktela.connector.DaktelaConnector;
import com.daktela.connector.DaktelaResponse;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;

// Create connector
DaktelaConnector connector = DaktelaConnector.builder()
    .instance("your-instance.daktela.com")
    .accessToken("your-access-token")
    .build();

// Get tickets
DaktelaResponse response = connector.get("tickets",
    DaktelaQuery.builder()
        .filter(DaktelaFilter.eq("stage", "OPEN"))
        .sort(DaktelaSort.desc("created"))
        .pagination(10, 0)
        .build());

// Process results
if (response.isSuccess()) {
    System.out.println("Total: " + response.getTotal());
    for (var ticket : response.getDataAsList()) {
        System.out.println(ticket.get("title"));
    }
}
```

## Configuration

```java
DaktelaConnector connector = DaktelaConnector.builder()
    .instance("your-instance.daktela.com")  // Required
    .accessToken("your-access-token")        // Required
    .timeout(Duration.ofSeconds(30))         // Optional (default: 30s)
    .authMethod(AuthMethod.HEADER)           // Optional (default: HEADER)
    .userAgent("MyApp/1.0")                  // Optional
    .build();
```

### Authentication Methods

- `AuthMethod.HEADER` - Sends token in `X-AUTH-TOKEN` header (default)
- `AuthMethod.QUERY` - Sends token as `accessToken` query parameter

## HTTP Methods

### GET

```java
// Get single record
DaktelaResponse response = connector.get("tickets/123");

// Get with query parameters
DaktelaResponse response = connector.get("tickets",
    DaktelaQuery.builder()
        .fields("name", "title", "category")
        .filter(DaktelaFilter.eq("stage", "OPEN"))
        .sort(DaktelaSort.desc("created"))
        .pagination(10, 0)
        .build());
```

### POST

```java
DaktelaResponse response = connector.post("tickets", Map.of(
    "title", "New ticket",
    "category", "support"
));
```

### PUT

```java
DaktelaResponse response = connector.put("tickets/123", Map.of(
    "title", "Updated title"
));
```

### DELETE

```java
DaktelaResponse response = connector.delete("tickets/123");
```

## Query Builder

### Fields

Select specific fields to retrieve:

```java
DaktelaQuery.builder()
    .fields("name", "title", "category", "stage")
    .build();
```

### Filters

| Operator | Method | Example |
|----------|--------|---------|
| Equal | `DaktelaFilter.eq(field, value)` | `eq("stage", "OPEN")` |
| Not Equal | `DaktelaFilter.neq(field, value)` | `neq("stage", "CLOSED")` |
| Greater Than | `DaktelaFilter.gt(field, value)` | `gt("priority", 3)` |
| Greater Than or Equal | `DaktelaFilter.gte(field, value)` | `gte("created", "2024-01-01")` |
| Less Than | `DaktelaFilter.lt(field, value)` | `lt("priority", 5)` |
| Less Than or Equal | `DaktelaFilter.lte(field, value)` | `lte("edited", "2024-12-31")` |
| Like | `DaktelaFilter.like(field, value)` | `like("title", "urgent")` |
| In | `DaktelaFilter.in(field, values)` | `in("stage", "OPEN", "NEW")` |
| Not In | `DaktelaFilter.notIn(field, values)` | `notIn("stage", "CLOSED")` |

#### Multiple Filters (AND)

```java
DaktelaQuery.builder()
    .filter(DaktelaFilter.eq("stage", "OPEN"))
    .filter(DaktelaFilter.gte("created", "2024-01-01"))
    .build();
```

#### OR Filters

```java
DaktelaQuery.builder()
    .filter(DaktelaFilter.or(
        DaktelaFilter.eq("stage", "OPEN"),
        DaktelaFilter.eq("stage", "NEW")
    ))
    .build();
```

### Sorting

```java
DaktelaQuery.builder()
    .sort(DaktelaSort.desc("created"))
    .sort(DaktelaSort.asc("name"))
    .build();
```

### Pagination

```java
DaktelaQuery.builder()
    .pagination(10, 0)   // take 10, skip 0
    .build();

// Or separately
DaktelaQuery.builder()
    .take(10)
    .skip(20)
    .build();
```

## Response Handling

```java
DaktelaResponse response = connector.get("tickets");

// Check status
int status = response.getStatus();
boolean success = response.isSuccess();  // true for 2xx

// Get data
Object data = response.getData();                        // Raw data
List<Map<String, Object>> list = response.getDataAsList();  // As list
Map<String, Object> map = response.getDataAsMap();       // As map

// Convert to custom type
MyTicket ticket = response.getDataAs(MyTicket.class);

// Pagination info
int total = response.getTotal();

// Error handling
if (response.hasErrors()) {
    List<Object> errors = response.getErrors();
}
```

## Exception Handling

```java
try {
    DaktelaResponse response = connector.get("tickets");
} catch (DaktelaUnauthorizedException e) {
    // 401 - Invalid or expired token
    System.err.println("Auth failed: " + e.getMessage());
} catch (DaktelaNotFoundException e) {
    // 404 - Resource not found
    System.err.println("Not found: " + e.getMessage());
} catch (DaktelaException e) {
    // Other API errors
    System.err.println("Error " + e.getStatusCode() + ": " + e.getMessage());
    Object errorData = e.getErrorData();
}
```

## Building from Source

### Maven

```bash
mvn clean package
```

### Gradle

```bash
gradle build
```

## Running Tests

### Maven

```bash
mvn test
```

### Gradle

```bash
gradle test
```

## License

MIT License
