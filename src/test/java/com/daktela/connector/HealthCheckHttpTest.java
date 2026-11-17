package com.daktela.connector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckHttpTest extends HttpTestSupport {

    @Test
    void healthyWhenWhoimIdentifiesTheUser() {
        reply(200, "{\"error\":[],\"result\":{\"user\":{\"name\":\"admin\"},\"version\":{}}}");

        DaktelaHealthCheck health = connector().healthCheck();

        assertTrue(health.isHealthy());
        assertEquals(Integer.valueOf(200), health.getStatusCode());
        assertNull(health.getError());
        assertFalse(health.getLatency().isNegative());
        assertEquals("GET", lastRequest().method);
        assertEquals("/api/v6/whoim.json", lastRequest().path);
    }

    @Test
    void latencyCoversTheResponseTime() {
        replies.add(new Reply(200, "{\"result\":{\"user\":{\"name\":\"admin\"}}}", Map.of(), 80));

        Duration latency = connector().healthCheck().getLatency();

        assertTrue(latency.compareTo(Duration.ofMillis(70)) >= 0, latency.toString());
    }

    @Test
    void rejectedTokenIsUnhealthy() {
        // whoim is public: a token it does not accept still gets HTTP 200, with a null user.
        reply(200, "{\"error\":[],\"result\":{\"user\":null,\"version\":{}}}");

        DaktelaHealthCheck health = connector().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(200), health.getStatusCode());
        assertEquals("The access token was not accepted: the API did not identify a user.", health.getError());
    }

    @Test
    void responseThatIsNotWhoimIsUnhealthy() {
        reply(200, "{\"result\":{\"data\":[],\"total\":0}}");

        DaktelaHealthCheck health = connector().healthCheck();

        assertFalse(health.isHealthy());
        assertTrue(health.getError().contains("check the instance URL"), health.getError());
    }

    @Test
    void apiErrorMessageIsReported() {
        reply(400, "{\"error\":{\"error\":\"New password should be set\"},\"result\":null}");

        DaktelaHealthCheck health = connector().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(400), health.getStatusCode());
        assertEquals("New password should be set", health.getError());
    }

    @Test
    void firstNestedErrorMessageIsReported() {
        reply(400, "{\"error\":[{\"form\":{\"title\":\" \"}}, [\"\", \"Nested message\"]]}");
        reply(400, "{\"error\":\"Plain message\"}");
        DaktelaConnector connector = connector();

        assertEquals("Nested message", connector.healthCheck().getError());
        assertEquals("Plain message", connector.healthCheck().getError());
    }

    @Test
    void emptyOrInvalidSuccessfulResponseIsUnhealthy() {
        reply(200, "");
        reply(200, "<html>maintenance</html>");
        DaktelaConnector connector = connector();

        DaktelaHealthCheck empty = connector.healthCheck();
        assertFalse(empty.isHealthy());
        assertEquals(Integer.valueOf(200), empty.getStatusCode());
        assertTrue(empty.getError().contains("check the instance URL"), empty.getError());

        DaktelaHealthCheck invalid = connector.healthCheck();
        assertFalse(invalid.isHealthy());
        assertEquals(Integer.valueOf(200), invalid.getStatusCode());
        assertTrue(invalid.getError().startsWith("Invalid JSON"), invalid.getError());
    }

    @Test
    void httpErrorWithoutMessageIsReported() {
        reply(401, "");

        DaktelaHealthCheck health = connector().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(401), health.getStatusCode());
        assertEquals("Unauthorized (HTTP 401)", health.getError());
    }

    @Test
    void networkErrorIsUnhealthyWithoutStatus() {
        DaktelaConnector connector = connector();
        server.stop(0);

        DaktelaHealthCheck health = connector.healthCheck();

        assertFalse(health.isHealthy());
        assertNull(health.getStatusCode());
        assertTrue(health.getError().startsWith("Network error"), health.getError());
    }

    @Test
    void errorEchoingTheTokenIsRedacted() {
        reply(400, "{\"error\":[\"Bad token secret-token\"]}");

        assertEquals("Bad token ***", connector().healthCheck().getError());
    }

    @Test
    void pingReflectsTheHealthCheck() {
        reply(200, "{\"result\":{\"user\":{\"name\":\"admin\"}}}");
        reply(200, "{\"result\":{\"user\":null}}");
        DaktelaConnector connector = connector();

        assertTrue(connector.ping());
        assertFalse(connector.ping());
    }

    @Test
    void toStringDescribesTheResult() {
        DaktelaHealthCheck health = new DaktelaHealthCheck(false, Duration.ofMillis(12), 400, "Broken");

        assertEquals("DaktelaHealthCheck{healthy=false, latency=12 ms, statusCode=400, error=Broken}", health.toString());
    }
}
