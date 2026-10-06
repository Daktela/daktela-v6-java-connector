# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Automated releases: pushing a `vX.Y.Z` tag tests, signs and publishes to Maven Central and
  creates the GitHub release. See [RELEASING.md](RELEASING.md).

## [1.1.0] - 2026-10-06

### Fixed
- Single-record reads (`get("tickets/123")`), creates (`post`) and updates (`put`) returned an
  empty `getDataAsMap()`: the record is now read from `result` as the API returns it.
- `DaktelaFilter.or(...)` produced a filter the API rejected with HTTP 400. Filters are now sent in
  the Kendo format the API documents (`filter[logic]=and&filter[filters][0][field]=...`), and OR
  groups work.
- `DaktelaFilter.notIn(...)` sent the unsupported operator `nin`; it now sends `notin`.
- Non-JSON error responses (for example an HTML 502 page from a proxy) were reported as a
  "Network error" with status 0. They now raise `DaktelaException` with the real HTTP status and
  the raw body.
- A `total` that was null or a numeric string caused a `ClassCastException` or
  `NullPointerException`.
- Endpoints containing spaces, `?` or `#` threw `IllegalArgumentException` or changed the request.
  Path segments are now URL-encoded, and `.`/`..` segments are rejected.
- `instance("https://my.daktela.com")` produced `https://https://...`. Hostnames and base URLs are
  both accepted now.
- An access token with a trailing newline, for example read from a secret file, made the JDK throw an
  exception whose message contained the token. The builder now rejects such tokens with a message
  that does not include the token.
- 3xx responses were returned as successful responses with no data. They now raise
  `DaktelaException`.
- `getDataAs(MyType.class)` failed as soon as the API returned a field the type did not declare.
  Unknown properties are now ignored.
- `eq(field, null)` and `neq(field, null)` sent the literal string `"null"`. They now create
  `isnull` and `isnotnull` filters.
- Nesting `or(...)` inside `or(...)` threw a `NullPointerException`.
- The compiler now targets the Java 11 API with `--release 11`, so the published jar cannot depend
  on newer JDK APIs by accident.

### Added
- `connector.getAll(endpoint, query)` reads every page of a list endpoint.
- `maxRetries(n)` on the builder: retries 429 responses (honouring `Retry-After`) and, for GET,
  network errors and 502/503/504, with exponential backoff. Off by default.
- `DaktelaForbiddenException` (403) and `DaktelaRateLimitException` (429, with `getRetryAfter()`).
- `DaktelaException.getResponseBody()`.
- `AuthMethod.BEARER` sends the token as `Authorization: Bearer`.
- New filters: `contains`, `doesNotContain`, `startsWith`, `endsWith`, `notLike`, `between`,
  `isNull`, `isNotNull`, the nestable `and(...)` group, and `of(field, operator, value)` for any
  other operator.
- `DaktelaResponse.getDataAs(TypeReference)`, `getDataAsListOf(Class)` and `hasTotal()`.
- `DaktelaQuery.toBuilder()` and `DaktelaQuery.Builder.param(name, value)` for additional query
  parameters.
- `httpClient(...)` and `objectMapper(...)` on the builder for proxies, custom TLS or custom JSON
  mapping.
- `java.time` support: `LocalDateTime` is read and written as `yyyy-MM-dd HH:mm:ss` in bodies,
  typed conversions and filter values. This adds a dependency on `jackson-datatype-jsr310`.
- The jar manifest now includes `Automatic-Module-Name: com.daktela.connector`.
- CI that builds and tests on JDK 11, 17 and 21, with actions pinned to commit SHAs and
  Dependabot updates.

### Changed
- `.json` is appended to endpoint paths, as the official PHP connector does. Paths that already
  end in `.json` are left unchanged.
- Path segments are URL-encoded. If you pre-encoded record names (`"contacts/a%20b"`), pass the
  raw name instead.
- Endpoints containing `?` are rejected with `IllegalArgumentException`. Pass extra query
  parameters with `DaktelaQuery.Builder.param(name, value)` instead.
- `instance(...)` must be a hostname or an `https://` base URL. `http://` is accepted only for
  localhost, so the token is never sent in cleartext. Paths, queries and credentials in the
  instance are rejected.
- Exception messages now include the HTTP status and the API's error details, for example
  `Request failed (HTTP 400): [...]`.
- Booleans in filter values are sent as `1`/`0`, and `LocalDate`/`LocalDateTime` values in API
  format.
- `DaktelaFilter.toMap()` returns the Kendo shape `{logic, filters}` for groups, instead of
  `{or: [...]}`.
- `DaktelaResponse.toString()` no longer prints record data, which could contain personal data.
- The default User-Agent reports the real library version (`DaktelaJavaConnector/1.1.0`).
- Stricter input checks, which fail fast with `IllegalArgumentException` or `NullPointerException`
  instead of producing a request the API rejects:
  - `DaktelaQuery.Builder.take()` requires a positive value, and `skip()` a non-negative one.
  - `in(...)`/`notIn(...)` with no values, and `or()`/`and()` with no filters, are rejected.
  - Null field names, filters and sorts are rejected.
  - The connector builder rejects a blank instance or token, a token containing whitespace or
    control characters, a non-positive timeout, negative `maxRetries` and null settings.
- Jackson upgraded to 2.22.3 and JUnit to 5.14.4. Maven plugins upgraded.

### Deprecated
- `DaktelaFilter.getOrFilters()`: use `getFilters()` together with `getLogic()`.

### Removed
- The Gradle build files. Maven is the single build and publishing path. Gradle users can still
  depend on the published artifact.

## [1.0.0] - 2026-01-22

### Added
- Initial release: `DaktelaConnector` with GET/POST/PUT/DELETE, the query builder (fields,
  filters, sorting, pagination), the response wrapper and the exception hierarchy.

[Unreleased]: https://github.com/Daktela/daktela-v6-java-connector/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/Daktela/daktela-v6-java-connector/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Daktela/daktela-v6-java-connector/releases/tag/v1.0.0
