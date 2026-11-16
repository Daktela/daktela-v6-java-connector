package com.daktela.connector;

import com.daktela.connector.auth.TokenSet;
import com.daktela.connector.auth.TokenStore;
import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoginSessionHttpTest extends HttpTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String access1 = jwt("a1", clock.instant().plus(Duration.ofHours(1)));
    private final String refresh1 = jwt("r1", clock.instant().plus(Duration.ofDays(14)));
    private final String access2 = jwt("a2", clock.instant().plus(Duration.ofHours(2)));
    private final String refresh2 = jwt("r2", clock.instant().plus(Duration.ofDays(15)));

    private DaktelaConnector.Builder loginBuilder() {
        return DaktelaConnector.builder()
                .instance(instance())
                .login("jirka", "hunter2")
                .timeout(Duration.ofSeconds(5))
                .sleeper(sleeps::add)
                .clock(clock);
    }

    private void tokens(String accessToken, String refreshToken) {
        reply(200, "{\"error\":[],\"result\":{\"accessToken\":\"" + accessToken
                + "\",\"accessTokenExpirationDate\":\"2026-11-16 14:00:00\",\"refreshToken\":\"" + refreshToken
                + "\",\"refreshTokenExpirationDate\":\"2026-11-30 13:00:00\"}}");
    }

    private void ok() {
        reply(200, "{\"error\":[],\"result\":{\"data\":[],\"total\":0}}");
    }

    private static Map<?, ?> json(String body) throws Exception {
        return JSON.readValue(body, Map.class);
    }

    private List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Recorded request : requests) {
            paths.add(request.method + " " + request.path);
        }
        return paths;
    }

    @Test
    void logsInOnTheFirstRequestAndSendsTheSessionToken() throws Exception {
        tokens(access1, refresh1);
        ok();

        loginBuilder().build().get("tickets");

        Recorded login = requests.get(0);
        assertEquals("POST /api/v6/login.json", login.method + " " + login.path);
        assertNull(login.token);
        assertNull(login.authorization);
        assertNull(login.query);
        assertEquals(Map.of("username", "jirka", "password", "hunter2", "only_token", "true",
                "client_side_refresh", "true"), json(login.body));
        assertEquals(access1, requests.get(1).token);
    }

    @Test
    void sessionIsReusedAcrossRequests() {
        tokens(access1, refresh1);
        ok();
        ok();
        DaktelaConnector connector = loginBuilder().build();

        connector.get("tickets");
        connector.get("users");

        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/tickets.json", "GET /api/v6/users.json"), paths());
    }

    @Test
    void sessionTokenUsesTheConfiguredAuthMethod() {
        tokens(access1, refresh1);
        ok();

        loginBuilder().authMethod(AuthMethod.BEARER).build().get("tickets");

        assertNull(requests.get(0).authorization);
        assertEquals("Bearer " + access1, requests.get(1).authorization);
    }

    @Test
    void accessTokenAboutToExpireIsRefreshedFirst() throws Exception {
        tokens(access1, refresh1);
        ok();
        tokens(access2, refresh2);
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");

        clock.advance(Duration.ofMinutes(59).plusSeconds(30));
        connector.get("tickets");

        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/tickets.json",
                "POST /api/v6/refreshAccessToken.json", "GET /api/v6/tickets.json"), paths());
        assertEquals(Map.of("refresh_token", refresh1), json(requests.get(2).body));
        assertNull(requests.get(2).token);
        assertEquals(access2, requests.get(3).token);
    }

    @Test
    void failedRefreshKeepsUsingAValidToken() {
        tokens(access1, refresh1);
        ok();
        reply(503, "");
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");

        clock.advance(Duration.ofMinutes(59).plusSeconds(30));
        assertTrue(connector.get("tickets").isSuccess());

        assertEquals(access1, lastRequest().token);
    }

    @Test
    void tokenThatLooksExpiredWhenIssuedIsNotRefreshedBeforeEveryRequest() {
        // The local clock is ahead of the server: refreshing before each request would not help.
        tokens(jwt("a1", clock.instant().plusSeconds(30)), refresh1);
        ok();
        ok();
        DaktelaConnector connector = loginBuilder().build();

        connector.get("tickets");
        connector.get("tickets");

        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/tickets.json", "GET /api/v6/tickets.json"), paths());
    }

    @Test
    void unauthorizedRequestIsRefreshedAndSentOnceMore() {
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        reply(201, "{\"result\":{\"name\":\"t1\"}}");

        DaktelaResponse response = loginBuilder().build().post("tickets", Map.of("title", "x"));

        assertEquals("t1", response.getDataAsMap().get("name"));
        assertEquals(List.of("POST /api/v6/login.json", "POST /api/v6/tickets.json",
                "POST /api/v6/refreshAccessToken.json", "POST /api/v6/tickets.json"), paths());
        assertEquals(access2, lastRequest().token);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void secondUnauthorizedIsThrown() {
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        reply(401, "");

        assertThrows(DaktelaUnauthorizedException.class, () -> loginBuilder().build().get("tickets"));
        assertEquals(4, requests.size());
    }

    @Test
    void rejectedRefreshLogsInAgain() {
        tokens(access1, refresh1);
        reply(401, "");
        reply(400, "{\"error\":[\"Invalid refresh token or token expired\"],\"result\":[]}");
        tokens(access2, refresh2);
        ok();

        assertTrue(loginBuilder().build().get("tickets").isSuccess());

        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/tickets.json",
                "POST /api/v6/refreshAccessToken.json", "POST /api/v6/login.json", "GET /api/v6/tickets.json"), paths());
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void expiredRefreshTokenLogsInInsteadOfRefreshing() {
        tokens(access1, jwt("r1", clock.instant().plus(Duration.ofHours(1))));
        ok();
        tokens(access2, refresh2);
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");

        clock.advance(Duration.ofMinutes(61));
        connector.get("tickets");

        assertEquals("POST /api/v6/login.json", requests.get(2).method + " " + requests.get(2).path);
    }

    @Test
    void refreshTokenIsKeptWhenTheServerDoesNotRotateIt() throws Exception {
        tokens(access1, refresh1);
        reply(401, "");
        reply(200, "{\"result\":{\"accessToken\":\"" + access2 + "\"}}");
        ok();
        reply(401, "");
        tokens(access2, refresh2);
        ok();
        DaktelaConnector connector = loginBuilder().build();

        connector.get("tickets");
        connector.get("tickets");

        assertEquals(Map.of("refresh_token", refresh1), json(requests.get(5).body));
    }

    @Test
    void rejectedLoginIsNotRepeated() {
        reply(401, "");
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaUnauthorizedException.class, () -> connector.get("tickets"));
        DaktelaUnauthorizedException again = assertThrows(DaktelaUnauthorizedException.class,
                () -> connector.get("tickets"));

        assertEquals(1, requests.size());
        assertTrue(again.getMessage().contains("rejected the login"), again.getMessage());
    }

    @Test
    void loginErrorEchoingThePasswordIsRedacted() {
        reply(400, "{\"error\":{\"password\":\"hunter2 is wrong\"},\"result\":null}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(400, e.getStatusCode());
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertFalse(e.getResponseBody().contains("hunter2"), e.getResponseBody());
        assertEquals(List.of(Map.of("password", "*** is wrong")), e.getErrorData());
    }

    @Test
    void sessionTokensEchoedInErrorsAreRedacted() {
        tokens(access1, refresh1);
        reply(400, "{\"error\":[\"" + access1 + " / " + refresh1 + "\"]}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(List.of("*** / ***"), e.getErrorData());
    }

    @Test
    void replacedTokensAreStillRedacted() {
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        reply(400, "{\"error\":[\"old " + access1 + "\"]}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(List.of("old ***"), e.getErrorData());
    }

    @Test
    void loginResponseWithoutTokensFails() {
        reply(200, "{\"result\":{\"user\":{\"name\":\"jirka\"}}}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(200, e.getStatusCode());
        assertTrue(e.getMessage().contains("does not contain an access token and a refresh token"), e.getMessage());
    }

    @Test
    void invalidTokenFromTheServerIsRejectedWithoutQuotingIt() {
        tokens("bad token", refresh1);

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertFalse(e.getMessage().contains("bad token"), e.getMessage());
        assertEquals(1, requests.size());
    }

    @Test
    void storedTokensOfTheSameSessionAreUsedWithoutLogin() {
        RecordingStore store = new RecordingStore(TokenSet.of(access1, refresh1, "jirka@" + instance()));
        ok();

        loginBuilder().tokenStore(store).build().get("tickets");

        assertEquals(List.of("GET /api/v6/tickets.json"), paths());
        assertEquals(access1, lastRequest().token);
    }

    @Test
    void storedTokensOfAnotherSessionAreIgnored() {
        RecordingStore store = new RecordingStore(TokenSet.of(access1, refresh1, "someone@" + instance()));
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(store).build().get("tickets");

        assertEquals("POST /api/v6/login.json", paths().get(0));
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void newTokensAreSavedAfterLoginAndRefresh() {
        RecordingStore store = new RecordingStore(null);
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(store).build().get("tickets");

        assertEquals(2, store.saved.size());
        assertEquals(access1, store.saved.get(0).getAccessToken());
        assertEquals("jirka@" + instance(), store.saved.get(0).getOwner());
        assertEquals(access2, store.saved.get(1).getAccessToken());
        assertEquals(refresh2, store.saved.get(1).getRefreshToken());
    }

    @Test
    void failingTokenStoreIsIgnored() {
        TokenStore broken = new TokenStore() {
            @Override
            public TokenSet load() {
                throw new IllegalStateException("cache down");
            }

            @Override
            public void save(TokenSet tokens) {
                throw new IllegalStateException("cache down");
            }
        };
        tokens(access1, refresh1);
        ok();

        assertTrue(loginBuilder().tokenStore(broken).build().get("tickets").isSuccess());
    }

    @Test
    void healthCheckRefreshesASessionTheServerHasEnded() {
        tokens(access1, refresh1);
        reply(200, "{\"result\":{\"user\":null}}");
        tokens(access2, refresh2);
        reply(200, "{\"result\":{\"user\":{\"name\":\"jirka\"}}}");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertTrue(health.isHealthy(), String.valueOf(health));
        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/whoim.json",
                "POST /api/v6/refreshAccessToken.json", "GET /api/v6/whoim.json"), paths());
    }

    @Test
    void healthCheckReportsARejectedLogin() {
        reply(401, "");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(401), health.getStatusCode());
    }

    @Test
    void builderRejectsInvalidLoginSettings() {
        assertThrows(IllegalArgumentException.class, () -> loginBuilder().accessToken("token").build());
        assertThrows(IllegalArgumentException.class, () -> builder().tokenStore(new RecordingStore(null)).build());
        assertThrows(IllegalArgumentException.class, () -> loginBuilder()
                .httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()).build());
        assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.builder()
                .instance(instance()).login(" ", "pw").build());
        assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.builder()
                .instance(instance()).login("jirka", "").build());
        assertThrows(NullPointerException.class, () -> DaktelaConnector.builder()
                .instance(instance()).login("jirka", null).build());
        NullPointerException none = assertThrows(NullPointerException.class,
                () -> DaktelaConnector.builder().instance(instance()).build());
        assertTrue(none.getMessage().contains("login"), none.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    void storedTokensAreCheckedForExpiry() {
        RecordingStore store = new RecordingStore(TokenSet.of(access1,
                jwt("r1", clock.instant().minusSeconds(1)), "jirka@" + instance()));
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(store).build().get("tickets");

        assertEquals("POST /api/v6/login.json", paths().get(0));
    }

    private static final class RecordingStore implements TokenStore {
        private final TokenSet stored;
        final List<TokenSet> saved = new ArrayList<>();

        RecordingStore(TokenSet stored) {
            this.stored = stored;
        }

        @Override
        public TokenSet load() {
            return stored;
        }

        @Override
        public void save(TokenSet tokens) {
            saved.add(tokens);
        }
    }
}
