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
    <version>1.2.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.daktela:daktela-v6-java-connector:1.2.0'
```

## Quick Start

```java
import com.daktela.connector.DaktelaConnector;
import com.daktela.connector.DaktelaResponse;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;

// Create connector (thread-safe - create once, reuse)
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
    .accessToken("your-access-token")        // Required, or login(username, password)
    .timeout(Duration.ofSeconds(30))         // Optional (default: 30s)
    .authMethod(AuthMethod.HEADER)           // Optional (default: HEADER)
    .userAgent("MyApp/1.0")                  // Optional (default: DaktelaJavaConnector/<version>)
    .maxRetries(3)                           // Optional (default: 0, no retries)
    .httpClient(customHttpClient)            // Optional, e.g. for a proxy or custom TLS (see below)
    .objectMapper(customObjectMapper)        // Optional, for custom JSON mapping
    .build();
```

A custom `HttpClient` should not follow redirects (the default one does not): a client that follows
them sends the `X-AUTH-TOKEN` header or the `accessToken` parameter to the redirect target, even on
another host. The connector logs a warning when it is built with such a client, and a future major
version will refuse it.

`instance` accepts `my.daktela.com`, `https://my.daktela.com` or `https://my.daktela.com/`;
`https://` is assumed when no scheme is given. A base URL may have a path prefix for an instance
behind a reverse proxy, such as `https://example.com/daktela`; a trailing `/api/v6` is optional.
Plain `http://` is accepted only for `localhost`, so the token is never sent in cleartext.

### Authentication Methods

- `AuthMethod.HEADER` - Sends token in `X-AUTH-TOKEN` header (default). Every Daktela V6
  version accepts it.
- `AuthMethod.BEARER` - Sends token in `Authorization: Bearer` header, the method the Daktela API
  documentation recommends. It requires Daktela 31.37.0, 32.36.0, 33.14.0 or later; older
  versions answer HTTP 401.
- `AuthMethod.QUERY` - Sends token as `accessToken` query parameter. Avoid where possible: the
  token becomes part of every URL and can end up in proxy and server logs.

### Access tokens

Use a static API token for integrations. An administrator creates it in
Manage → Users → [user detail] → Persistent API tokens.

Since Daktela 2025.2 static tokens expire (after up to 720 days, optionally extended on each
use). An expired, deactivated or deleted token makes every request fail with
`DaktelaUnauthorizedException`, so check the expiration date when you set up an integration.

### Logging in with a username and password

If you have no static token, log in with a username and password instead:

```java
DaktelaConnector connector = DaktelaConnector.builder()
    .instance("your-instance.daktela.com")
    .login("username", "password")
    .build();
```

The connector logs in with the first request. It refreshes the access token about 60 seconds before
it expires and after an HTTP 401, and logs in again when the server has ended the session. Concurrent
requests share one refresh. If a refresh fails while the current access token is still valid,
requests keep using that token and the refresh is tried again after 15 seconds.

If the server rejects the login (HTTP 400, 401 or 403, or an answer without tokens), the connector
sends no further login requests, because repeated attempts could lock the account. Later requests on
that connector fail without contacting the server, so create a new connector with valid credentials.
After any other failed login, such as a rate limit or a server or network error, the connector waits
before it sends the password again: 10 seconds, doubling with each failure in a row up to 5 minutes,
or the server's `Retry-After` when that is longer (up to 1 hour). Requests in the meantime fail
without contacting the server. If a login is interrupted because its calling thread is interrupted,
only that request fails; a second interruption before a login succeeds starts the same wait.

Accounts with two-factor authentication cannot log in this way; use an access token for them.
`login(...)` cannot be combined with `accessToken(...)`, or with an `HttpClient` that follows
redirects, because the password is sent in the request body.

To share one session between processes, or keep it across restarts, pass a `TokenStore`:

```java
TokenStore tokenStore = new TokenStore() {
    @Override
    public TokenSet load() {
        // secrets is your secure storage, for example an encrypted database table
        String access = secrets.get("daktela.access");
        String refresh = secrets.get("daktela.refresh");
        return access == null || refresh == null
                ? null
                : TokenSet.of(access, refresh, secrets.get("daktela.owner"));
    }

    @Override
    public void save(TokenSet tokens) {
        secrets.put("daktela.access", tokens.getAccessToken());
        secrets.put("daktela.refresh", tokens.getRefreshToken());
        secrets.put("daktela.owner", tokens.getOwner());
    }
};

DaktelaConnector connector = DaktelaConnector.builder()
    .instance("your-instance.daktela.com")
    .login("username", "password")
    .tokenStore(tokenStore)
    .build();
```

`TokenSet.of(access, refresh, owner)` restores saved tokens. The connector ignores stored tokens
of another owner (`username@instance URL`) and tokens whose refresh token has expired, and logs in
instead. Refresh tokens are long-lived, so store them as securely as passwords. If the store throws,
the connector logs a warning. If `load()` throws, it logs in; if `save()` throws, it keeps the new
tokens in memory.

The owner check is not authentication: the password is not checked against stored tokens, so a
connector built with the right username and any password resumes the stored session. Give each
party that holds a password its own store, for example one per tenant.

### Retries

With `maxRetries(n)` the connector retries:

- HTTP 429 for every method, waiting for `Retry-After` when the server sends it (a longer wait than
  60 s is not made: the 429 exception is thrown instead);
- network errors and HTTP 502/503/504 for `GET` only, because a failed write may already have
  taken effect;
- connection failures (the connection could not be established) for every method, because the
  server never received the request. Writes (`POST`, `PUT`, `DELETE`) are retried this way only
  with an `HttpClient` that does not follow redirects; the default client does not follow them. A
  client that follows redirects could fail on a redirect target after the server has processed the
  write.

Delays grow exponentially from 500 ms.

## Health Check

```java
DaktelaHealthCheck check = connector.healthCheck();
check.isHealthy();       // true when the API is reachable and accepts the credentials
check.getLatency();      // Duration, including any token refresh and retries
check.getStatusCode();   // HTTP status, or null when no response was received
check.getError();        // why it is unhealthy, null when healthy

boolean up = connector.ping();   // same as healthCheck().isHealthy()
```

`healthCheck()` reads the `whoim` endpoint and never throws a `DaktelaException`; failures are
reported in the result. The API answers HTTP 200 with no user for a token it does not accept, so
the check is healthy only when the response identifies a user. With a login session, a response
without a user triggers one token refresh and one more check.

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

`stream` follows the same rules but reads one page only when the stream reaches it, so large result
sets do not have to fit in memory. A failed page request throws `DaktelaException` from the terminal
operation.

```java
connector.stream("contacts", DaktelaQuery.builder()
        .sort(DaktelaSort.asc("name"))
        .take(500)
        .build())
    .map(contact -> contact.get("title"))
    .forEach(System.out::println);
```

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
    // 401 - Invalid or expired token, or a login the server rejected
} catch (DaktelaForbiddenException e) {
    // 403 - Token lacks permission for this object, or a login the server rejected
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

Access and refresh tokens are replaced with `***` in exception messages, `getResponseBody()`,
`getErrorData()` and `DaktelaResponse.getErrors()`, in case the server echoes them. During a login,
the password is replaced too.

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
