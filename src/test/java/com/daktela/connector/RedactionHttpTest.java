package com.daktela.connector;

import com.daktela.connector.exception.DaktelaException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The access token must not reach exception messages, stored bodies or API errors, even when the
 * server echoes it.
 */
class RedactionHttpTest extends HttpTestSupport {

    @Test
    void tokenEchoedInAnErrorIsRedactedEverywhere() {
        reply(400, "{\"error\":[\"Token secret-token is not valid\"],\"result\":null}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals("Request failed (HTTP 400): [Token *** is not valid]", e.getMessage());
        assertEquals(List.of("Token *** is not valid"), e.getErrorData());
        assertFalse(e.getResponseBody().contains("secret-token"), e.getResponseBody());
        assertTrue(e.getResponseBody().contains("Token *** is not valid"), e.getResponseBody());
    }

    @Test
    void urlEncodedAndJsonEscapedFormsAreRedacted() {
        // A token with characters that URL and JSON encoding change.
        String token = "ab/cd+ef~g*h";
        reply(502, "<p>GET /api/v6/tickets.json?accessToken=ab%2Fcd%2Bef%7Eg*h</p>"
                + "<p>accessToken=ab%2Fcd%2Bef~g%2Ah</p><p>\"ab\\/cd+ef~g*h\"</p>");

        DaktelaException e = assertThrows(DaktelaException.class,
                () -> builder().accessToken(token).authMethod(AuthMethod.QUERY).build().get("tickets"));

        for (String text : List.of(e.getMessage(), e.getResponseBody())) {
            assertFalse(text.contains("ab%2Fcd"), text);
            assertFalse(text.contains("ab\\/cd"), text);
            assertFalse(text.contains("ab/cd"), text);
        }
    }

    @Test
    void tokenInErrorMapKeysAndNestedValuesIsRedacted() {
        reply(400, "{\"error\":{\"form\":{\"secret-token\":\"bad\"},\"primary\":[\"secret-token\"]}}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertEquals(List.of(Map.of("form", Map.of("***", "bad"), "primary", List.of("***"))), e.getErrorData());
        assertFalse(e.getMessage().contains("secret-token"), e.getMessage());
    }

    @Test
    void errorsOfASuccessfulResponseAreRedacted() {
        reply(200, "{\"error\":[\"secret-token expires soon\"],\"result\":{\"data\":[],\"total\":0}}");

        DaktelaResponse response = connector().get("tickets");

        assertEquals(List.of("*** expires soon"), response.getErrors());
    }

    @Test
    void invalidJsonEchoingTheTokenIsRedacted() {
        reply(200, "oops secret-token");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector().get("tickets"));

        assertFalse(e.getMessage().contains("secret-token"), e.getMessage());
        assertEquals("oops ***", e.getResponseBody());
        // The parser's exception is not chained: its message can quote the body.
        assertNull(e.getCause());
    }

    @Test
    void networkErrorQuotingTheTokenIsRedactedIncludingTheCause() {
        IOException failure = new IOException("failed GET /api/v6/tickets.json?accessToken=secret-token");
        DaktelaConnector connector = builder().authMethod(AuthMethod.QUERY)
                .httpClient(new FailingHttpClient(failure, HttpClient.Redirect.NEVER)).build();

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertEquals("Network error: failed GET /api/v6/tickets.json?accessToken=***", e.getMessage());
        assertTrue(e.getCause() instanceof IOException);
        assertFalse(String.valueOf(e.getCause()).contains("secret-token"), String.valueOf(e.getCause()));
        assertNull(e.getCause().getCause());
    }

    @Test
    void networkErrorCauseKeepsItsTypeAndSafeChain() {
        ConnectException withSecret = new ConnectException("refused for secret-token");
        withSecret.initCause(new IOException("inner secret-token"));
        DaktelaException redacted = assertThrows(DaktelaException.class, () -> builder()
                .httpClient(new FailingHttpClient(withSecret, HttpClient.Redirect.NEVER)).build().get("tickets"));
        assertTrue(redacted.getCause() instanceof ConnectException);
        assertEquals("refused for ***", redacted.getCause().getMessage());
        assertNull(redacted.getCause().getCause());

        ConnectException clean = new ConnectException("Connection refused");
        DaktelaException untouched = assertThrows(DaktelaException.class, () -> builder()
                .httpClient(new FailingHttpClient(clean, HttpClient.Redirect.NEVER)).build().get("tickets"));
        assertSame(clean, untouched.getCause());
    }

    @Test
    void cyclicCauseChainDoesNotHang() {
        IOException first = new IOException("first");
        IOException second = new IOException("second");
        first.initCause(second);
        second.initCause(first);

        DaktelaException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                DaktelaException.class, () -> builder()
                        .httpClient(new FailingHttpClient(first, HttpClient.Redirect.NEVER)).build().get("tickets")));

        assertSame(first, e.getCause());
    }

    @Test
    void causeChainTooLongToCheckIsReplaced() {
        IOException top = new IOException("top");
        Throwable link = top;
        for (int i = 0; i < 40; i++) {
            IOException next = new IOException("level " + i);
            link.initCause(next);
            link = next;
        }
        DaktelaException e = assertThrows(DaktelaException.class, () -> builder()
                .httpClient(new FailingHttpClient(top, HttpClient.Redirect.NEVER)).build().get("tickets"));

        assertNotSame(top, e.getCause());
        assertNull(e.getCause().getCause());
    }

    @Test
    void secretInASuppressedExceptionIsDropped() {
        IOException failure = new IOException("connection reset");
        failure.addSuppressed(new IOException("while sending accessToken=secret-token"));

        DaktelaException e = assertThrows(DaktelaException.class, () -> builder()
                .httpClient(new FailingHttpClient(failure, HttpClient.Redirect.NEVER)).build().get("tickets"));

        assertNotSame(failure, e.getCause());
        assertEquals(0, e.getCause().getSuppressed().length);
        assertEquals("connection reset", e.getCause().getMessage());
    }

    @Test
    void redactorReplacesEveryFormOfASecretWhole() {
        String secret = "a\"b\\c/d+e~f*g h";
        Redactor redactor = Redactor.of(List.of(secret));

        for (String form : List.of(
                secret,
                "a%22b%5Cc%2Fd%2Be%7Ef*g+h",
                "a%22b%5Cc%2Fd%2Be~f%2Ag%20h",
                "a\\\"b\\\\c/d+e~f*g h",
                "a\\\"b\\\\c\\/d+e~f*g h")) {
            assertEquals("[***]", redactor.redact("[" + form + "]"), form);
        }
    }

    @Test
    void successfulDataIsNotRedacted() {
        reply(200, "{\"result\":{\"name\":\"secret-token\"}}");

        assertEquals("secret-token", connector().get("tickets/1").getDataAsMap().get("name"));
    }

    @Test
    void redactorReplacesLongerFormsFirst() {
        Redactor redactor = Redactor.of(List.of("abc", "abc/def"));

        assertEquals("x *** y ***", redactor.redact("x abc/def y abc"));
        assertEquals("plain", Redactor.of(List.of("")).redact("plain"));
        assertNull(redactor.redact(null));
    }
}
