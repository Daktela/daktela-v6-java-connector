# Daktela V6 Java Connector

[![CI](https://github.com/Daktela/daktela-v6-java-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/Daktela/daktela-v6-java-connector/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.daktela/daktela-v6-java-connector)](https://central.sonatype.com/artifact/com.daktela/daktela-v6-java-connector)

Java SDK for [Daktela V6 REST API](https://www.daktela.com/).

## Requirements

- Java 11 or higher

## Installation

### Maven

```xml
<dependency>
    <groupId>com.daktela</groupId>
    <artifactId>daktela-v6-java-connector</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.daktela:daktela-v6-java-connector:1.1.0'
```

## Quick Start

```java
import com.daktela.connector.DaktelaConnector;
import com.daktela.connector.DaktelaResponse;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;

// Create connector (immutable and thread-safe - create once, reuse)
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

System.out.println("Total: " + response.getTotal());
for (var ticket : response.getDataAsList()) {
    System.out.println(ticket.get("title"));
}
```

## Configuration

```java
DaktelaConnector connector = DaktelaConnector.builder()
    .instance("your-instance.daktela.com")  // Required; hostname or https:// base URL
    .accessToken("your-access-token")        // Required
    .timeout(Duration.ofSeconds(30))         // Optional (default: 30s)
    .authMethod(AuthMethod.HEADER)           // Optional (default: HEADER)
    .userAgent("MyApp/1.0")                  // Optional (default: DaktelaJavaConnector/<version>)
    .maxRetries(3)                           // Optional (default: 0, no retries)
    .httpClient(customHttpClient)            // Optional, e.g. for a proxy or custom TLS
    .objectMapper(customObjectMapper)        // Optional, for custom JSON mapping
    .build();
```

`instance` accepts `my.daktela.com`, `https://my.daktela.com` or `https://my.daktela.com/`;
`https://` is assumed when no scheme is given. Plain `http://` is accepted only for `localhost`,
so the token is never sent in cleartext.

### Authentication Methods

- `AuthMethod.HEADER` - Sends token in `X-AUTH-TOKEN` header (default)
- `AuthMethod.BEARER` - Sends token in `Authorization: Bearer` header
- `AuthMethod.QUERY` - Sends token as `accessToken` query parameter. Avoid where possible: the
  token becomes part of every URL and can end up in proxy and server logs.

### Retries

With `maxRetries(n)` the connector retries:

- HTTP 429 for every method, waiting for `Retry-After` when the server sends it (up to 60 s);
- network errors and HTTP 502/503/504 for `GET` only, because a failed write may already have
  taken effect.

Delays grow exponentially from 500 ms.

## HTTP Methods

Endpoints are given without the `/api/v6/` prefix. Path segments are URL-encoded for you, so pass
record names as they are (`"contacts/" + name`); do not pre-encode them. The `.json` suffix is
added automatically. Endpoints must not contain a query string: pass extra parameters with
`DaktelaQuery.Builder.param(name, value)`.

### GET

```java
// Get single record
DaktelaResponse response = connector.get("tickets/123");
Map<String, Object> ticket = response.getDataAsMap();

// Get with query parameters
DaktelaResponse response = connector.get("tickets",
    DaktelaQuery.builder()
        .fields("name", "title", "category")
        .filter(DaktelaFilter.eq("stage", "OPEN"))
        .sort(DaktelaSort.desc("created"))
        .pagination(10, 0)
        .build());
```

### Read all pages

```java
List<Map<String, Object>> all = connector.getAll("tickets",
    DaktelaQuery.builder()
        .filter(DaktelaFilter.eq("stage", "OPEN"))
        .sort(DaktelaSort.asc("name"))
        .take(500)   // page size (default 100, API maximum 1000)
        .build());
```

`getAll` keeps requesting pages until it has `total` records or receives a short page.
Sort by a stable field when paging over data that may change while you read it.

### POST

```java
DaktelaResponse response = connector.post("tickets", Map.of(
    "title", "New ticket",
    "category", "support"
));
Map<String, Object> created = response.getDataAsMap();  // the created record
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
| Equal | `eq(field, value)` | `eq("stage", "OPEN")`; `eq(field, null)` means is null |
| Not Equal | `neq(field, value)` | `neq("stage", "CLOSED")` |
| Greater Than | `gt(field, value)` | `gt("priority", 3)` |
| Greater Than or Equal | `gte(field, value)` | `gte("created", "2024-01-01")` |
| Less Than | `lt(field, value)` | `lt("priority", 5)` |
| Less Than or Equal | `lte(field, value)` | `lte("edited", "2024-12-31")` |
| Between (inclusive) | `between(field, from, to)` | `between("created", "2024-01-01", "2024-12-31")` |
| Contains | `contains(field, text)` | `contains("title", "urgent")` |
| Does Not Contain | `doesNotContain(field, text)` | `doesNotContain("title", "spam")` |
| Starts With | `startsWith(field, text)` | `startsWith("number", "+420")` |
| Ends With | `endsWith(field, text)` | `endsWith("email", "@daktela.com")` |
| Like (raw SQL pattern) | `like(field, pattern)` | `like("title", "%urgent%")` |
| Not Like | `notLike(field, pattern)` | `notLike("title", "%test%")` |
| In | `in(field, values)` | `in("stage", "OPEN", "NEW")` |
| Not In | `notIn(field, values)` | `notIn("stage", "CLOSED")` |
| Is Null | `isNull(field)` | `isNull("user")` |
| Is Not Null | `isNotNull(field)` | `isNotNull("user")` |
| Any other operator | `of(field, operator, value)` | `of("number", "regexp", "^\\+420")` |

All methods are static on `DaktelaFilter`. Values of type `LocalDateTime` are sent as
`yyyy-MM-dd HH:mm:ss`, `LocalDate` as `yyyy-MM-dd`, and booleans as `1`/`0`.

Note that `like` uses the value as-is, so include `%` wildcards yourself; `contains` wraps the
value for you.

#### Multiple Filters (AND)

```java
DaktelaQuery.builder()
    .filter(DaktelaFilter.eq("stage", "OPEN"))
    .filter(DaktelaFilter.gte("created", "2024-01-01"))
    .build();
```

#### OR and nested groups

```java
// stage = OPEN OR stage = NEW
DaktelaQuery.builder()
    .filter(DaktelaFilter.or(
        DaktelaFilter.eq("stage", "OPEN"),
        DaktelaFilter.eq("stage", "NEW")
    ))
    .build();

// category = support AND (stage = OPEN OR (stage = WAIT AND user IS NULL))
DaktelaQuery.builder()
    .filter(DaktelaFilter.eq("category", "support"))
    .filter(DaktelaFilter.or(
        DaktelaFilter.eq("stage", "OPEN"),
        DaktelaFilter.and(
            DaktelaFilter.eq("stage", "WAIT"),
            DaktelaFilter.isNull("user"))))
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

### Other query parameters

```java
DaktelaQuery.builder()
    .param("q", "search text")   // sent as-is (URL-encoded)
    .build();
```

## Response Handling

```java
DaktelaResponse response = connector.get("tickets");

// Check status
int status = response.getStatus();
boolean success = response.isSuccess();  // true for 2xx

// Get data
Object data = response.getData();                           // Raw data
List<Map<String, Object>> list = response.getDataAsList();  // List endpoints
Map<String, Object> map = response.getDataAsMap();          // Single record, create, update

// Convert to custom types (unknown JSON properties are ignored)
MyTicket ticket = response.getDataAs(MyTicket.class);
List<MyTicket> tickets = response.getDataAsListOf(MyTicket.class);
List<MyTicket> same = response.getDataAs(new TypeReference<List<MyTicket>>() {});

// Pagination info
int total = response.getTotal();      // 0 when the API did not report a total
boolean known = response.hasTotal();

// Application-level errors on a successful response
if (response.hasErrors()) {
    List<Object> errors = response.getErrors();
}
```

`LocalDateTime` fields in your types are read and written in the API's `yyyy-MM-dd HH:mm:ss`
format.

## Exception Handling

Every failure is a `DaktelaException` (unchecked). HTTP errors map to specific subclasses:

```java
try {
    DaktelaResponse response = connector.post("tickets", Map.of("title", "x"));
} catch (DaktelaUnauthorizedException e) {
    // 401 - Invalid or expired token
} catch (DaktelaForbiddenException e) {
    // 403 - Token lacks permission for this object
} catch (DaktelaNotFoundException e) {
    // 404 - Resource not found
} catch (DaktelaRateLimitException e) {
    // 429 - Too many requests; e.getRetryAfter() says how long to wait (may be null)
} catch (DaktelaException e) {
    // Other API errors (e.g. 400 validation), network errors (status 0), invalid responses
    int status = e.getStatusCode();
    Object errorData = e.getErrorData();   // list of API errors, e.g. [{"form": {"title": "Required"}, "primary": []}]
    String body = e.getResponseBody();     // raw body, also for non-JSON proxy error pages
}
```

## Building from Source

The build uses Maven and needs JDK 11 or newer:

```bash
mvn clean verify
```

## Releasing

See [RELEASING.md](RELEASING.md).

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

MIT License
