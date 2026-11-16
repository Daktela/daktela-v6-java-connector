package com.daktela.connector;

import com.daktela.connector.exception.DaktelaException;
import org.junit.jupiter.api.Test;

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
