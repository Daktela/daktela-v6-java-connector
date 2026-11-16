package com.daktela.connector;

import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaForbiddenException;
import com.daktela.connector.exception.DaktelaNotFoundException;
import com.daktela.connector.exception.DaktelaRateLimitException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.daktela.connector.query.DaktelaFilter;
import com.daktela.connector.query.DaktelaQuery;
import com.daktela.connector.query.DaktelaSort;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the connector against a local HTTP server to pin down the exact wire format
 * and response handling.
 */
class DaktelaConnectorHttpTest extends HttpTestSupport {

    // --- URL and query string ---------------------------------------------------------------

    @Test
    void endpointGetsJsonSuffixAndTokenHeader() {
        connector().get("tickets");

        Recorded request = lastRequest();
        assertEquals("GET", request.method);
        assertEquals("/api/v6/tickets.json", request.path);
        assertNull(request.query);
        assertEquals("secret-token", request.token);
        assertTrue(request.userAgent.startsWith("DaktelaJavaConnector/"));
    }

    @Test
    void leadingSlashAndExistingJsonSuffixAreNormalized() {
        connector().get("/tickets/123.json");

        assertEquals("/api/v6/tickets/123.json", lastRequest().path);
    }

    @Test
    void pathSegmentsAreEncoded() {
        connector().get("contacts/john doe#x+y");

        assertEquals("/api/v6/contacts/john%20doe%23x%2By.json", lastRequest().path);
    }

    @Test
    void dotSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> connector().get("tickets/../users"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void queryAuthSendsTokenAsParameterOnly() {
        builder().authMethod(AuthMethod.QUERY).build().get("tickets");

        Recorded request = lastRequest();
        assertNull(request.token);
        assertEquals(List.of("accessToken=secret-token"), request.decodedParams());
    }

    @Test
    void bearerAuthUsesAuthorizationHeader() {
        builder().authMethod(AuthMethod.BEARER).build().get("tickets");

        Recorded request = lastRequest();
        assertNull(request.token);
        assertEquals("Bearer secret-token", request.authorization);
        assertNull(request.query);
    }

    @Test
    void fieldsSortsAndPaginationAreSerialized() {
        connector().get("tickets", DaktelaQuery.builder()
                .fields("name", "title")
                .sort(DaktelaSort.desc("edited"))
                .pagination(10, 20)
                .build());

        assertEquals(List.of(
                "fields[0]=name",
                "fields[1]=title",
                "sort[0][field]=edited",
                "sort[0][dir]=desc",
                "take=10",
                "skip=20"), lastRequest().decodedParams());
    }

    @Test
    void filtersUseKendoFormatWithAndLogic() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("stage", "OPEN"))
                .filter(DaktelaFilter.in("category", "a", "b"))
                .build());

        assertEquals(List.of(
                "filter[logic]=and",
                "filter[filters][0][field]=stage",
                "filter[filters][0][operator]=eq",
                "filter[filters][0][value]=OPEN",
                "filter[filters][1][field]=category",
                "filter[filters][1][operator]=in",
                "filter[filters][1][value][0]=a",
                "filter[filters][1][value][1]=b"), lastRequest().decodedParams());
    }

    @Test
    void singleOrGroupIsSentAsTopLevelGroup() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.or(
                        DaktelaFilter.eq("stage", "OPEN"),
                        DaktelaFilter.eq("stage", "WAIT")))
                .build());

        assertEquals(List.of(
                "filter[logic]=or",
                "filter[filters][0][field]=stage",
                "filter[filters][0][operator]=eq",
                "filter[filters][0][value]=OPEN",
                "filter[filters][1][field]=stage",
                "filter[filters][1][operator]=eq",
                "filter[filters][1][value]=WAIT"), lastRequest().decodedParams());
    }

    @Test
    void orGroupNextToOtherFiltersIsNested() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.eq("category", "support"))
                .filter(DaktelaFilter.or(
                        DaktelaFilter.eq("stage", "OPEN"),
                        DaktelaFilter.and(
                                DaktelaFilter.eq("stage", "WAIT"),
                                DaktelaFilter.isNull("user"))))
                .build());

        assertEquals(List.of(
                "filter[logic]=and",
                "filter[filters][0][field]=category",
                "filter[filters][0][operator]=eq",
                "filter[filters][0][value]=support",
                "filter[filters][1][logic]=or",
                "filter[filters][1][filters][0][field]=stage",
                "filter[filters][1][filters][0][operator]=eq",
                "filter[filters][1][filters][0][value]=OPEN",
                "filter[filters][1][filters][1][logic]=and",
                "filter[filters][1][filters][1][filters][0][field]=stage",
                "filter[filters][1][filters][1][filters][0][operator]=eq",
                "filter[filters][1][filters][1][filters][0][value]=WAIT",
                "filter[filters][1][filters][1][filters][1][field]=user",
                "filter[filters][1][filters][1][filters][1][operator]=isnull"), lastRequest().decodedParams());
    }

    @Test
    void filterValuesAreFormattedForTheApi() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.gte("created", LocalDateTime.of(2024, 1, 2, 3, 4, 5)))
                .filter(DaktelaFilter.lte("created", LocalDate.of(2024, 12, 31)))
                .filter(DaktelaFilter.eq("closed", true))
                .filter(DaktelaFilter.eq("title", "a&b=c d"))
                .build());

        List<String> params = lastRequest().decodedParams();
        assertTrue(params.contains("filter[filters][0][value]=2024-01-02 03:04:05"), params.toString());
        assertTrue(params.contains("filter[filters][1][value]=2024-12-31"), params.toString());
        assertTrue(params.contains("filter[filters][2][value]=1"), params.toString());
        assertTrue(params.contains("filter[filters][3][value]=a&b=c d"), params.toString());
    }

    @Test
    void notInAndBetweenSendValueArrays() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.notIn("stage", "CLOSE"))
                .filter(DaktelaFilter.between("priority", 1, 3))
                .build());

        assertEquals(List.of(
                "filter[logic]=and",
                "filter[filters][0][field]=stage",
                "filter[filters][0][operator]=notin",
                "filter[filters][0][value][0]=CLOSE",
                "filter[filters][1][field]=priority",
                "filter[filters][1][operator]=between",
                "filter[filters][1][value][0]=1",
                "filter[filters][1][value][1]=3"), lastRequest().decodedParams());
    }

    @Test
    void customOperatorWithArrayValueSendsValueArray() {
        connector().get("tickets", DaktelaQuery.builder()
                .filter(DaktelaFilter.of("stage", "in", new String[]{"OPEN", "WAIT"}))
                .build());

        assertTrue(lastRequest().decodedParams().containsAll(List.of(
                "filter[filters][0][value][0]=OPEN", "filter[filters][0][value][1]=WAIT")));
    }

    @Test
    void endpointWithQueryStringIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> connector().get("tickets?stage=OPEN"));

        assertTrue(e.getMessage().contains("param("), e.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    void customParamsAreAppended() {
        connector().get("tickets", DaktelaQuery.builder().param("q", "hello world").build());

        assertEquals(List.of("q=hello world"), lastRequest().decodedParams());
    }

    // --- Response parsing -------------------------------------------------------------------

    @Test
    void listResponseExposesDataAndTotal() {
        reply(200, "{\"error\":[],\"result\":{\"data\":[{\"name\":\"t1\"},{\"name\":\"t2\"}],\"total\":42}}");

        DaktelaResponse response = connector().get("tickets");

        assertEquals(2, response.getDataAsList().size());
        assertEquals("t1", response.getDataAsList().get(0).get("name"));
        assertEquals(42, response.getTotal());
        assertTrue(response.hasTotal());
        assertFalse(response.hasErrors());
    }

    @Test
    void singleRecordIsReadFromResultDirectly() {
        reply(200, "{\"error\":[],\"result\":{\"name\":\"123\",\"title\":\"Hello\"}}");

        DaktelaResponse response = connector().get("tickets/123");

        assertEquals("Hello", response.getDataAsMap().get("title"));
        assertFalse(response.hasTotal());
    }

    @Test
    void createdRecordIsReturnedFromPost() {
        reply(201, "{\"error\":[],\"result\":{\"name\":\"456\",\"title\":\"New\"}}");

        DaktelaResponse response = connector().post("tickets", Map.of("title", "New"));

        assertEquals("456", response.getDataAsMap().get("name"));
        Recorded request = lastRequest();
        assertEquals("POST", request.method);
        assertEquals("application/json", request.contentType);
        assertEquals("{\"title\":\"New\"}", request.body);
    }

    @Test
    void requestBodySerializesDatesInApiFormat() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deadline", LocalDateTime.of(2024, 5, 6, 7, 8, 9));
        body.put("day", LocalDate.of(2024, 5, 6));

        connector().put("tickets/1", body);

        assertEquals("{\"deadline\":\"2024-05-06 07:08:09\",\"day\":\"2024-05-06\"}", lastRequest().body);
    }

    @Test
    void recordWithDataFieldIsNotMistakenForList() {
        reply(200, "{\"result\":{\"name\":\"1\",\"data\":[\"x\"],\"title\":\"Has data field\"}}");

        DaktelaResponse response = connector().get("tickets/1");

        assertEquals("Has data field", response.getDataAsMap().get("title"));
    }

    @Test
    void listWithoutTotalIsStillAList() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"}]}}");

        DaktelaResponse response = connector().get("tickets", DaktelaQuery.builder().param("total", "false").build());

        assertEquals(1, response.getDataAsList().size());
        assertFalse(response.hasTotal());
    }

    @Test
    void numericTotalAsStringIsAccepted() {
        reply(200, "{\"result\":{\"data\":[],\"total\":\"7\"}}");

        assertEquals(7, connector().get("tickets").getTotal());
    }

    @Test
    void getDataAsIgnoresUnknownPropertiesAndParsesApiDates() {
        reply(200, "{\"result\":{\"name\":\"1\",\"created\":\"2024-01-02 03:04:05\",\"brandNewField\":true}}");

        Ticket ticket = connector().get("tickets/1").getDataAs(Ticket.class);

        assertEquals("1", ticket.name);
        assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), ticket.created);
    }

    @Test
    void listCanBeConvertedToTypedElements() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"a\"},{\"name\":\"b\"}],\"total\":2}}");

        DaktelaResponse response = connector().get("tickets");

        assertEquals("b", response.getDataAsListOf(Ticket.class).get(1).name);
        List<Ticket> viaReference = response.getDataAs(new TypeReference<List<Ticket>>() { });
        assertEquals("a", viaReference.get(0).name);
    }

    @Test
    void successWithApplicationErrorsIsReturnedNotThrown() {
        reply(200, "{\"error\":[\"Something minor\"],\"result\":{\"name\":\"1\"}}");

        DaktelaResponse response = connector().get("tickets/1");

        assertTrue(response.hasErrors());
        assertEquals(List.of("Something minor"), response.getErrors());
    }

    @Test
    void emptyBodyGivesEmptyResponse() {
        reply(204, "");

        DaktelaResponse response = connector().delete("tickets/1");

        assertTrue(response.isSuccess());
        assertNull(response.getData());
        assertEquals("DELETE", lastRequest().method);
    }

    @Test
    void toStringDoesNotLeakRecordData() {
        reply(200, "{\"result\":{\"email\":\"jane@example.com\"}}");

        String text = connector().get("contacts/1").toString();

        assertFalse(text.contains("jane@example.com"), text);
    }

    // --- Errors -----------------------------------------------------------------------------

    @Test
    void validationErrorKeepsStatusAndErrorPayload() {
        // Shape produced by Mapper::getErrors on a failed save.
        reply(400, "{\"error\":{\"form\":{\"title\":\"Required\"},\"primary\":[]},\"result\":{\"title\":null}}");

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> connector().post("tickets", Map.of()));

        assertEquals(400, e.getStatusCode());
        // Same shape as 1.0.0: the error payload wrapped in a list.
        assertEquals(List.of(Map.of("form", Map.of("title", "Required"), "primary", List.of())), e.getErrorData());
        assertTrue(e.getMessage().contains("Required"), e.getMessage());
    }

    @Test
    void apiErrorListIsUsedInMessage() {
        reply(400, "{\"error\":[\"Max defined take is 1000\"],\"result\":null}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals("Request failed (HTTP 400): [Max defined take is 1000]", e.getMessage());
    }

    @Test
    void nullErrorStillThrowsWithStatus() {
        reply(400, "{\"error\":null,\"result\":null}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals(400, e.getStatusCode());
    }

    @Test
    void statusCodesMapToSpecificExceptions() {
        // The API answers 401, 403 and 404 with an empty body.
        reply(401, "");
        reply(403, "");
        reply(404, "");
        reply(429, "");
        DaktelaConnector connector = connector();

        assertThrows(DaktelaUnauthorizedException.class, () -> connector.get("tickets"));
        assertThrows(DaktelaForbiddenException.class, () -> connector.get("tickets"));
        assertThrows(DaktelaNotFoundException.class, () -> connector.get("tickets/1"));
        assertThrows(DaktelaRateLimitException.class, () -> connector.get("tickets"));
    }

    @Test
    void htmlErrorPageKeepsHttpStatusAndBody() {
        reply(502, "<html><body>Bad Gateway</body></html>");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals(502, e.getStatusCode());
        assertTrue(e.getMessage().contains("HTTP 502"), e.getMessage());
        assertEquals("<html><body>Bad Gateway</body></html>", e.getResponseBody());
    }

    @Test
    void redirectIsAnError() {
        replies.add(new Reply(301, "", Map.of("Location", "https://elsewhere.example/")));

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> connector().post("tickets", Map.of("title", "x")));

        assertEquals(301, e.getStatusCode());
    }

    @Test
    void invalidJsonOnSuccessIsReportedAsSuch() {
        reply(200, "not json");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals(200, e.getStatusCode());
        assertTrue(e.getMessage().startsWith("Invalid JSON"), e.getMessage());
    }

    @Test
    void networkErrorIsWrapped() {
        DaktelaConnector connector = builder().build();
        server.stop(0);

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertEquals(0, e.getStatusCode());
        assertTrue(e.getCause() instanceof IOException);
    }

    // --- Retries ----------------------------------------------------------------------------

    @Test
    void noRetriesByDefault() {
        reply(503, "");

        assertThrows(DaktelaException.class, () -> connector().get("tickets"));
        assertEquals(1, requests.size());
    }

    @Test
    void rateLimitIsRetriedHonouringRetryAfter() {
        replies.add(new Reply(429, "{\"error\":[\"Too many requests\"]}", Map.of("Retry-After", "3")));
        reply(201, "{\"result\":{\"name\":\"1\"}}");

        DaktelaResponse response = builder().maxRetries(2).build().post("tickets", Map.of("title", "x"));

        assertEquals("1", response.getDataAsMap().get("name"));
        assertEquals(2, requests.size());
        assertEquals(List.of(Duration.ofSeconds(3)), sleeps);
    }

    @Test
    void rateLimitExceptionCarriesRetryAfterWhenRetriesExhausted() {
        replies.add(new Reply(429, "", Map.of("Retry-After", "5")));

        DaktelaRateLimitException e = assertThrows(DaktelaRateLimitException.class,
                () -> connector().get("tickets"));

        assertEquals(Duration.ofSeconds(5), e.getRetryAfter());
    }

    @Test
    void transientServerErrorsAreRetriedForGetWithBackoff() {
        reply(503, "");
        reply(502, "");
        reply(200, "{\"result\":{\"data\":[],\"total\":0}}");

        DaktelaResponse response = builder().maxRetries(3).build().get("tickets");

        assertTrue(response.isSuccess());
        assertEquals(3, requests.size());
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(1000)), sleeps);
    }

    @Test
    void networkErrorsAreRetriedForGet() {
        server.stop(0);

        assertThrows(DaktelaException.class, () -> builder().maxRetries(2).build().get("tickets"));
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(1000)), sleeps);
    }

    @Test
    void writesAreNotRetriedWhenTheConnectionFailsAfterSending() {
        // The server may have processed the write before the connection broke.
        replies.add(new Reply(CLOSE_CONNECTION, ""));

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> builder().maxRetries(3).build().post("tickets", Map.of("title", "x")));

        assertTrue(e.getCause() instanceof IOException);
        assertFalse(e.getCause() instanceof java.net.ConnectException);
        assertEquals(1, requests.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void writesAreRetriedWhenTheConnectionCannotBeEstablished() {
        server.stop(0);
        DaktelaConnector connector = builder().maxRetries(2).build();

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> connector.post("tickets", Map.of("title", "x")));

        assertTrue(e.getCause() instanceof java.net.ConnectException, String.valueOf(e.getCause()));
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(1000)), sleeps);
    }

    @Test
    void writesAreNotRetriedOnConnectFailureWhenTheClientFollowsRedirects() {
        // A connection failure could then come from a redirect hop, after the server processed the write.
        server.stop(0);
        DaktelaConnector connector = builder().maxRetries(2)
                .httpClient(java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build())
                .build();

        assertThrows(DaktelaException.class, () -> connector.post("tickets", Map.of("title", "x")));
        assertTrue(sleeps.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void everyWriteIsRetriedWhenTheConnectionCannotBeEstablished(String method) {
        FailingHttpClient client = new FailingHttpClient(new ConnectException("Connection refused"), HttpClient.Redirect.NEVER);
        DaktelaConnector connector = builder().maxRetries(2).httpClient(client).build();

        assertThrows(DaktelaException.class, () -> write(connector, method));

        assertEquals(3, client.calls.get());
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofMillis(1000)), sleeps);
    }

    @Test
    void writesAreRetriedOnAConnectTimeout() {
        FailingHttpClient client = new FailingHttpClient(new HttpConnectTimeoutException("HTTP connect timed out"),
                HttpClient.Redirect.NEVER);

        assertThrows(DaktelaException.class,
                () -> builder().maxRetries(1).httpClient(client).build().post("tickets", Map.of("title", "x")));

        assertEquals(2, client.calls.get());
    }

    @Test
    void writesAreNotRetriedAfterARequestTimeout() {
        // The request was sent; only the response did not arrive in time.
        FailingHttpClient client = new FailingHttpClient(new HttpTimeoutException("request timed out"),
                HttpClient.Redirect.NEVER);

        assertThrows(DaktelaException.class,
                () -> builder().maxRetries(3).httpClient(client).build().post("tickets", Map.of("title", "x")));

        assertEquals(1, client.calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "ALWAYS"})
    void writesAreNotRetriedOnConnectFailureWithAnyRedirectPolicy(String redirect) {
        FailingHttpClient client = new FailingHttpClient(new ConnectException("Connection refused"),
                HttpClient.Redirect.valueOf(redirect));

        assertThrows(DaktelaException.class,
                () -> builder().maxRetries(3).httpClient(client).build().post("tickets", Map.of("title", "x")));

        assertEquals(1, client.calls.get());
    }

    private static void write(DaktelaConnector connector, String method) {
        switch (method) {
            case "POST":
                connector.post("tickets", Map.of("title", "x"));
                break;
            case "PUT":
                connector.put("tickets/1", Map.of("title", "x"));
                break;
            default:
                connector.delete("tickets/1");
        }
    }

    @Test
    void retryAfterBeyondLimitIsNotWaitedFor() {
        replies.add(new Reply(429, "{\"error\":[]}", Map.of("Retry-After", "3600")));

        DaktelaRateLimitException e = assertThrows(DaktelaRateLimitException.class,
                () -> builder().maxRetries(3).build().get("tickets"));

        assertEquals(Duration.ofHours(1), e.getRetryAfter());
        assertEquals(1, requests.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void interruptedRetryRestoresInterruptFlag() {
        reply(503, "");
        DaktelaConnector connector = builder().maxRetries(1)
                .sleeper(d -> { throw new InterruptedException(); })
                .build();

        try {
            DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));
            assertTrue(e.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void serverErrorsAreNotRetriedForWrites() {
        reply(503, "");

        assertThrows(DaktelaException.class,
                () -> builder().maxRetries(3).build().post("tickets", Map.of("title", "x")));
        assertEquals(1, requests.size());
    }

    // --- Pagination -------------------------------------------------------------------------

    @Test
    void getAllFollowsPagesUntilTotal() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}],\"total\":5}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"3\"},{\"name\":\"4\"}],\"total\":5}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"5\"}],\"total\":5}}");

        List<Map<String, Object>> all = connector().getAll("tickets",
                DaktelaQuery.builder().filter(DaktelaFilter.eq("stage", "OPEN")).take(2).build());

        assertEquals(5, all.size());
        assertEquals("5", all.get(4).get("name"));
        assertEquals(3, requests.size());
        assertTrue(requests.get(2).decodedParams().containsAll(List.of("take=2", "skip=4", "filter[filters][0][value]=OPEN")));
    }

    @Test
    void getAllStopsOnShortPageWithoutTotal() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}]}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"3\"}]}}");

        List<Map<String, Object>> all = connector().getAll("tickets", DaktelaQuery.builder().take(2).build());

        assertEquals(3, all.size());
        assertEquals(2, requests.size());
    }

    @Test
    void getAllStartsAtGivenSkipAndStopsOnEmptyPage() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}]}}");
        reply(200, "{\"result\":{\"data\":[]}}");

        List<Map<String, Object>> all = connector().getAll("tickets", DaktelaQuery.builder().pagination(2, 10).build());

        assertEquals(2, all.size());
        assertTrue(requests.get(0).decodedParams().contains("skip=10"));
        assertTrue(requests.get(1).decodedParams().contains("skip=12"));
    }

    @Test
    void getAllUsesDefaultPageSize() {
        connector().getAll("tickets", null);

        assertTrue(lastRequest().decodedParams().containsAll(List.of("take=100", "skip=0")));
    }

    @Test
    void streamFetchesPagesOnlyWhenConsumed() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}],\"total\":5}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"3\"},{\"name\":\"4\"}],\"total\":5}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"5\"}],\"total\":5}}");
        DaktelaConnector connector = connector();

        Stream<Map<String, Object>> stream = connector.stream("tickets", DaktelaQuery.builder().take(2).build());
        assertTrue(requests.isEmpty());

        List<Object> firstThree = stream.limit(3).map(r -> r.get("name")).collect(Collectors.toList());

        assertEquals(List.of("1", "2", "3"), firstThree);
        assertEquals(2, requests.size());
    }

    @Test
    void streamReadsEveryPage() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}],\"total\":3}}");
        reply(200, "{\"result\":{\"data\":[{\"name\":\"3\"}],\"total\":3}}");

        long count = connector().stream("tickets", DaktelaQuery.builder().take(2).build()).count();

        assertEquals(3, count);
        assertEquals(2, requests.size());
    }

    @Test
    void streamThrowsWhenALaterPageFails() {
        reply(200, "{\"result\":{\"data\":[{\"name\":\"1\"},{\"name\":\"2\"}],\"total\":4}}");
        reply(500, "{\"error\":[\"Database error\"]}");
        List<Object> seen = new java.util.ArrayList<>();

        Stream<Map<String, Object>> stream = connector().stream("tickets", DaktelaQuery.builder().take(2).build());
        DaktelaException e = assertThrows(DaktelaException.class, () -> stream.forEach(r -> seen.add(r.get("name"))));

        assertEquals(500, e.getStatusCode());
        assertEquals(List.of("1", "2"), seen);
    }

    @Test
    void streamStopsAtThePageLimit() {
        for (int i = 0; i < 4; i++) {
            reply(200, "{\"result\":{\"data\":[{\"name\":\"a\"},{\"name\":\"b\"}]}}");
        }
        DaktelaConnector connector = builder().maxPages(3).build();

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> connector.stream("tickets", DaktelaQuery.builder().take(2).build()).count());

        assertTrue(e.getMessage().contains("exceeded 3 pages"), e.getMessage());
        assertEquals(3, requests.size());
    }

    @Test
    void streamOfNonListDataIsEmpty() {
        reply(200, "{\"result\":{\"name\":\"x\"}}");

        assertEquals(0, connector().stream("tickets", null).count());
        assertEquals(1, requests.size());
    }

    @Test
    void streamRejectsAnInvalidEndpointImmediately() {
        assertThrows(IllegalArgumentException.class, () -> connector().stream("tickets/../users", null));
        assertTrue(requests.isEmpty());
    }

    // --- Instance with a path prefix --------------------------------------------------------

    @Test
    void instancePathPrefixIsKeptInRequests() {
        builder().instance(instance() + "/daktela/").build().get("tickets");

        assertEquals("/daktela/api/v6/tickets.json", lastRequest().path);
    }

    // --- Helpers ----------------------------------------------------------------------------

    public static class Ticket {
        public String name;
        public LocalDateTime created;
    }
}
