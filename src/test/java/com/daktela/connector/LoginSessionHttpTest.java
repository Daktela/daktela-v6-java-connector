package com.daktela.connector;

import com.daktela.connector.auth.TokenSet;
import com.daktela.connector.auth.TokenStore;
import com.daktela.connector.exception.DaktelaException;
import com.daktela.connector.exception.DaktelaForbiddenException;
import com.daktela.connector.exception.DaktelaRateLimitException;
import com.daktela.connector.exception.DaktelaUnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private long count(String methodAndPath) {
        return paths().stream().filter(methodAndPath::equals).count();
    }

    private static void concurrently(int threads, Callable<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
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

    @ParameterizedTest
    @ValueSource(ints = {400, 401})
    void rejectedRefreshLogsInAgain(int status) {
        tokens(access1, refresh1);
        reply(401, "");
        reply(status, status == 401 ? "" : "{\"error\":[\"Invalid refresh token or token expired\"],\"result\":[]}");
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
    void healthCheckRefreshesAnEndedSessionOnlyOnce() {
        tokens(access1, refresh1);
        reply(200, "{\"result\":{\"user\":null}}");
        tokens(access2, refresh2);
        reply(200, "{\"result\":{\"user\":null}}");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals("The access token was not accepted: the API did not identify a user.", health.getError());
        assertEquals(4, requests.size());
    }

    @Test
    void healthCheckReportsAFailedRefreshWithoutThrowing() {
        tokens(access1, refresh1);
        reply(200, "{\"result\":{\"user\":null}}");
        reply(503, "");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(503), health.getStatusCode());
        assertNotNull(health.getError());
    }

    @Test
    void healthCheckDoesNotRefreshOnAResponseThatIsNotWhoim() {
        tokens(access1, refresh1);
        reply(200, "{\"result\":{\"data\":[],\"total\":0}}");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertFalse(health.isHealthy());
        assertTrue(health.getError().contains("check the instance URL"), health.getError());
        assertEquals(2, requests.size());
    }

    @Test
    void healthCheckReportsARejectedLogin() {
        reply(401, "");

        DaktelaHealthCheck health = loginBuilder().build().healthCheck();

        assertFalse(health.isHealthy());
        assertEquals(Integer.valueOf(401), health.getStatusCode());
    }

    @Test
    void loginAndAccessTokenAreExclusive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loginBuilder().accessToken("token").build());
        assertTrue(e.getMessage().contains("not both"), e.getMessage());
    }

    @Test
    void tokenStoreNeedsLogin() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> builder().tokenStore(new RecordingStore(null)).build());
        assertTrue(e.getMessage().contains("tokenStore"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "ALWAYS"})
    void loginRejectsAClientThatFollowsRedirects(String redirect) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.valueOf(redirect)).build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loginBuilder().httpClient(client).build());
        assertTrue(e.getMessage().contains("redirects"), e.getMessage());
    }

    @Test
    void loginNeedsAUsernameAndPassword() {
        IllegalArgumentException blankUser = assertThrows(IllegalArgumentException.class,
                () -> DaktelaConnector.builder().instance(instance()).login(" ", "pw").build());
        assertTrue(blankUser.getMessage().contains("username"), blankUser.getMessage());
        IllegalArgumentException emptyPassword = assertThrows(IllegalArgumentException.class,
                () -> DaktelaConnector.builder().instance(instance()).login("jirka", "").build());
        assertTrue(emptyPassword.getMessage().contains("password"), emptyPassword.getMessage());
        NullPointerException nullPassword = assertThrows(NullPointerException.class,
                () -> DaktelaConnector.builder().instance(instance()).login("jirka", null).build());
        assertTrue(nullPassword.getMessage().contains("password"), nullPassword.getMessage());
        NullPointerException none = assertThrows(NullPointerException.class,
                () -> DaktelaConnector.builder().instance(instance()).build());
        assertTrue(none.getMessage().contains("login"), none.getMessage());
    }

    @Test
    void buildingTheConnectorSendsNoRequest() {
        loginBuilder().build();

        assertTrue(requests.isEmpty());
    }

    // --- Concurrency ------------------------------------------------------------------------

    @Test
    void concurrentFirstRequestsLogInOnce() throws Exception {
        tokens(access1, refresh1);
        DaktelaConnector connector = loginBuilder().build();

        concurrently(16, () -> connector.get("tickets"));

        assertEquals(1, count("POST /api/v6/login.json"));
        assertEquals(16, count("GET /api/v6/tickets.json"));
        for (Recorded request : requests.subList(1, requests.size())) {
            assertEquals(access1, request.token);
        }
    }

    @Test
    void concurrentRequestsRefreshAnExpiringTokenOnce() throws Exception {
        tokens(access1, refresh1);
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");
        clock.advance(Duration.ofMinutes(59).plusSeconds(30));
        requests.clear();
        tokens(access2, refresh2);

        concurrently(16, () -> connector.get("tickets"));

        assertEquals(1, count("POST /api/v6/refreshAccessToken.json"));
        for (Recorded request : requests.subList(1, requests.size())) {
            assertEquals(access2, request.token);
        }
    }

    @Test
    void unauthorizedTokenThatAnotherThreadReplacedIsNotRefreshedAgain() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        LoginSession.Transport transport = (endpoint, payload, secret) -> {
            calls.add(endpoint);
            return new DaktelaResponse(200, Map.of("accessToken", access1, "refreshToken", refresh1), null, null,
                    JSON);
        };
        LoginSession session = new LoginSession("jirka", "pw", "jirka@" + instance(), null, clock);
        assertEquals(access1, session.accessToken(transport));

        session.refreshAfterUnauthorized("an-older-token", transport);
        assertEquals(List.of("login"), calls);

        session.refreshAfterUnauthorized(access1, transport);
        assertEquals(List.of("login", "refreshAccessToken"), calls);
    }

    @Test
    void refreshThatFailedWhileOthersWaitedIsNotRepeatedByEach() throws Exception {
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        LoginSession.Transport transport = (endpoint, payload, secret) -> {
            if (endpoint.equals("login")) {
                return new DaktelaResponse(200, Map.of("accessToken", access1, "refreshToken", refresh1), null, null,
                        JSON);
            }
            refreshes.incrementAndGet();
            refreshing.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new DaktelaException("Request failed (HTTP 503)", 503);
        };
        LoginSession session = new LoginSession("jirka", "pw", "jirka@" + instance(), null, clock);
        session.accessToken(transport);
        clock.advance(Duration.ofHours(2));

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread thread = new Thread(() -> {
                try {
                    session.accessToken(transport);
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(thread);
            thread.start();
            if (i == 0) {
                assertTrue(refreshing.await(10, TimeUnit.SECONDS));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Thread waiting : threads.subList(1, threads.size())) {
            while (waiting.getState() != Thread.State.BLOCKED) {
                assertTrue(System.nanoTime() < deadline, "threads did not queue for the lock");
                Thread.sleep(5);
            }
        }
        release.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertEquals(1, refreshes.get());
        assertEquals(5, failures.size());
        for (Throwable failure : failures) {
            assertEquals(503, ((DaktelaException) failure).getStatusCode());
        }
    }

    // --- Failure paths ----------------------------------------------------------------------

    @Test
    void loginBackoffDoublesWithEachFailureInARow() {
        reply(503, "");
        reply(503, "");
        tokens(access1, refresh1);
        ok();
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaException.class, () -> connector.get("tickets"));
        clock.advance(LoginSession.LOGIN_RETRY_DELAY);
        assertThrows(DaktelaException.class, () -> connector.get("tickets"));
        clock.advance(LoginSession.LOGIN_RETRY_DELAY);
        DaktelaException waiting = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertTrue(waiting.getMessage().contains("next login is sent in 10 s"), waiting.getMessage());
        assertEquals(2, requests.size());
        clock.advance(LoginSession.LOGIN_RETRY_DELAY);
        assertTrue(connector.get("tickets").isSuccess());
        assertEquals(3, count("POST /api/v6/login.json"));
    }

    @Test
    void failedRefreshWithAValidTokenLogsOneWarningWithoutTokens() {
        tokens(access1, refresh1);
        ok();
        reply(503, "{\"error\":[\"down for " + refresh1 + "\"]}");
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");
        clock.advance(Duration.ofMinutes(59).plusSeconds(30));

        List<String> log = logOf(() -> connector.get("tickets"));

        assertEquals(1, log.size(), log.toString());
        assertTrue(log.get(0).startsWith("WARNING Refreshing the Daktela access token failed"), log.get(0));
        assertFalse(log.get(0).contains(refresh1) || log.get(0).contains(access1), log.get(0));
    }

    @Test
    void failedRefreshIsTriedAgainOnlyAfterAPause() {
        tokens(access1, refresh1);
        ok();
        reply(503, "");
        ok();
        ok();
        tokens(access2, refresh2);
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");
        clock.advance(Duration.ofMinutes(59));

        connector.get("tickets");
        connector.get("tickets");
        assertEquals(1, count("POST /api/v6/refreshAccessToken.json"));
        assertEquals(access1, lastRequest().token);

        clock.advance(LoginSession.REFRESH_RETRY_DELAY);
        connector.get("tickets");
        assertEquals(2, count("POST /api/v6/refreshAccessToken.json"));
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void rejectedLoginDoesNotResumeTheEndedSessionFromTheStore() {
        RecordingStore store = new RecordingStore(TokenSet.of(access1, refresh1, "jirka@" + instance()));
        reply(401, "");
        reply(400, "{\"error\":[\"Invalid refresh token or token expired\"]}");
        reply(401, "");
        DaktelaConnector connector = loginBuilder().tokenStore(store).build();

        assertThrows(DaktelaUnauthorizedException.class, () -> connector.get("tickets"));
        int sent = requests.size();
        assertThrows(DaktelaUnauthorizedException.class, () -> connector.get("tickets"));

        assertEquals(sent, requests.size());
    }

    @Test
    void nonAsciiPasswordEchoedAsUnicodeEscapesIsRedacted() {
        reply(400, "{\"error\":[\"bad password \\u017elu\\u0165ou\\u010dk\\u00fd\"]}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> DaktelaConnector.builder()
                .instance(instance()).login("jirka", "žluťoučký").sleeper(sleeps::add).clock(clock).build()
                .get("tickets"));

        assertFalse(e.getResponseBody().contains("u017e"), e.getResponseBody());
        assertEquals(List.of("bad password ***"), e.getErrorData());
    }

    @Test
    void failedRefreshOfAnExpiredTokenThrows() {
        tokens(access1, refresh1);
        ok();
        reply(503, "");
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertEquals(503, e.getStatusCode());
        assertEquals("POST /api/v6/refreshAccessToken.json", paths().get(paths().size() - 1));
    }

    @Test
    void loginRejectedWithBadRequestIsNotRepeated() {
        reply(400, "{\"error\":{\"form\":{\"password\":\"Wrong\"}},\"result\":null}");
        DaktelaConnector connector = loginBuilder().build();

        assertEquals(400, assertThrows(DaktelaException.class, () -> connector.get("tickets")).getStatusCode());
        DaktelaException again = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertFalse(again instanceof DaktelaUnauthorizedException);
        assertEquals(400, again.getStatusCode());
        assertEquals(1, requests.size());
    }

    @Test
    void loginRejectedAfterASessionEndedIsNotRepeated() {
        tokens(access1, refresh1);
        reply(401, "");
        reply(400, "{\"error\":[\"Invalid refresh token or token expired\"]}");
        reply(401, "");
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaUnauthorizedException.class, () -> connector.get("tickets"));
        int sent = requests.size();
        DaktelaUnauthorizedException again = assertThrows(DaktelaUnauthorizedException.class,
                () -> connector.get("tickets"));

        assertEquals(sent, requests.size());
        assertTrue(again.getMessage().contains("rejected the login"), again.getMessage());
    }

    @Test
    void loginServerErrorIsTriedAgainAfterAPause() {
        reply(503, "");
        tokens(access1, refresh1);
        ok();
        DaktelaConnector connector = loginBuilder().build();

        assertEquals(503, assertThrows(DaktelaException.class, () -> connector.get("tickets")).getStatusCode());
        DaktelaException coolingDown = assertThrows(DaktelaException.class, () -> connector.get("tickets"));
        assertEquals(503, coolingDown.getStatusCode());
        assertTrue(coolingDown.getMessage().contains("failed recently"), coolingDown.getMessage());
        assertEquals(1, requests.size());

        clock.advance(LoginSession.LOGIN_RETRY_DELAY);
        assertTrue(connector.get("tickets").isSuccess());
        assertEquals(2, count("POST /api/v6/login.json"));
    }

    @Test
    void rateLimitedLoginWaitsForRetryAfter() {
        replies.add(new Reply(429, "", Map.of("Retry-After", "30")));
        tokens(access1, refresh1);
        ok();
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaRateLimitException.class, () -> connector.get("tickets"));
        clock.advance(Duration.ofSeconds(20));
        DaktelaRateLimitException waiting = assertThrows(DaktelaRateLimitException.class,
                () -> connector.get("tickets"));
        assertEquals(Duration.ofSeconds(10), waiting.getRetryAfter());
        assertEquals(1, requests.size());

        clock.advance(Duration.ofSeconds(10));
        assertTrue(connector.get("tickets").isSuccess());
    }

    @Test
    void interruptedLoginDoesNotStartTheCoolDown() {
        tokens(access1, refresh1);
        ok();
        DaktelaConnector connector = loginBuilder().httpClient(new FailingHttpClient(new InterruptedException(), 1,
                HttpClient.newHttpClient(), HttpClient.Redirect.NEVER)).build();

        try {
            DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));
            assertTrue(e.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }

        assertTrue(connector.get("tickets").isSuccess());
        assertEquals(List.of("POST /api/v6/login.json", "GET /api/v6/tickets.json"), paths());
    }

    @Test
    void expiryAtTheStartOfTimeDoesNotBreakTheSession() {
        tokens(jwt("a1", java.time.Instant.MIN), refresh1);
        ok();

        assertTrue(loginBuilder().build().get("tickets").isSuccess());
    }

    @Test
    void repeatedlyInterruptedLoginsStartTheCoolDown() {
        // A server that answers failed logins slowly and a caller that times out must not
        // turn into an unthrottled stream of password attempts.
        tokens(access1, refresh1);
        ok();
        DaktelaConnector connector = loginBuilder().httpClient(new FailingHttpClient(new InterruptedException(), 2,
                HttpClient.newHttpClient(), HttpClient.Redirect.NEVER)).build();

        for (int i = 0; i < 2; i++) {
            try {
                assertThrows(DaktelaException.class, () -> connector.get("tickets"));
            } finally {
                Thread.interrupted();
            }
        }
        DaktelaException coolingDown = assertThrows(DaktelaException.class, () -> connector.get("tickets"));
        assertTrue(coolingDown.getMessage().contains("failed recently"), coolingDown.getMessage());
        assertTrue(requests.isEmpty());

        clock.advance(LoginSession.LOGIN_RETRY_DELAY);
        assertTrue(connector.get("tickets").isSuccess());
    }

    @Test
    void hugeRetryAfterOnALoginIsCapped() {
        replies.add(new Reply(429, "", Map.of("Retry-After", "99999999999999999")));
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaRateLimitException.class, () -> connector.get("tickets"));
        DaktelaRateLimitException waiting = assertThrows(DaktelaRateLimitException.class,
                () -> connector.get("tickets"));

        assertEquals(LoginSession.MAX_LOGIN_RETRY_DELAY, waiting.getRetryAfter());
        assertEquals(1, requests.size());
        assertFalse(connector.healthCheck().isHealthy());
    }

    @Test
    void loginForbiddenIsNotRepeated() {
        reply(403, "");
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaForbiddenException.class, () -> connector.get("tickets"));
        assertThrows(DaktelaForbiddenException.class, () -> connector.get("tickets"));

        assertEquals(1, requests.size());
    }

    @Test
    void loginAnswerWithoutTokensIsNotRepeated() {
        reply(200, "{\"result\":{\"twoFactor\":true}}");
        DaktelaConnector connector = loginBuilder().build();

        assertThrows(DaktelaException.class, () -> connector.get("tickets"));
        DaktelaException again = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertTrue(again.getMessage().contains("rejected the login"), again.getMessage());
        assertTrue(again.getMessage().contains("does not contain an access token"), again.getMessage());
        assertEquals(1, requests.size());
    }

    @Test
    void loginAnswerThatIsNotJsonDoesNotLeakTheNewTokens() {
        reply(200, "<b>Deprecated</b>: something {\"result\":{\"accessToken\":\"" + access1
                + "\",\"refreshToken\":\"" + refresh1 + "\"}}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(200, e.getStatusCode());
        assertFalse(e.getMessage().contains(access1) || e.getMessage().contains(refresh1), e.getMessage());
        assertNull(e.getResponseBody());
    }

    @Test
    void refreshAnswerThatIsNotJsonDoesNotLeakTheNewTokensIntoTheLog() {
        tokens(access1, refresh1);
        ok();
        reply(200, "<b>Deprecated</b>: {\"result\":{\"accessToken\":\"" + access2
                + "\",\"refreshToken\":\"" + refresh2 + "\"}}");
        ok();
        DaktelaConnector connector = loginBuilder().build();
        connector.get("tickets");
        clock.advance(Duration.ofMinutes(59).plusSeconds(30));

        List<String> log = logOf(() -> connector.get("tickets"));

        assertEquals(1, log.size(), log.toString());
        assertFalse(log.get(0).contains(access2) || log.get(0).contains(refresh2), log.get(0));
        assertEquals(access1, lastRequest().token);
    }

    @Test
    void failedStoreLoadIsLoggedWithoutItsMessage() {
        TokenStore leaky = new TokenStore() {
            @Override
            public TokenSet load() {
                throw new IllegalStateException("cannot parse {\"refresh\":\"" + refresh1 + "\"}");
            }

            @Override
            public void save(TokenSet tokens) {
            }
        };
        tokens(access2, refresh2);
        ok();

        List<String> log = logOf(() -> loginBuilder().tokenStore(leaky).build().get("tickets"));

        assertEquals(1, log.size(), log.toString());
        assertFalse(log.get(0).contains(refresh1), log.get(0));
        assertTrue(log.get(0).contains("IllegalStateException"), log.get(0));
    }

    @Test
    void passwordInANetworkErrorDuringLoginIsRedacted() {
        DaktelaConnector connector = loginBuilder()
                .httpClient(new FailingHttpClient(new IOException("refused login of jirka:hunter2"),
                        HttpClient.Redirect.NEVER))
                .build();

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertFalse(String.valueOf(e.getCause()).contains("hunter2"), String.valueOf(e.getCause()));
    }

    @Test
    void passwordIsNotRedactedOutsideALoginRequest() {
        // A short password would otherwise mangle unrelated text.
        tokens(access1, refresh1);
        reply(400, "{\"error\":[\"hunter2 is a ticket title\"]}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> loginBuilder().build().get("tickets"));

        assertEquals(List.of("hunter2 is a ticket title"), e.getErrorData());
    }

    @Test
    void onlyTheLastFourReplacedTokensAreRedacted() {
        String access3 = jwt("a3", clock.instant().plus(Duration.ofHours(3)));
        String access4 = jwt("a4", clock.instant().plus(Duration.ofHours(4)));
        tokens(access1, refresh1);
        DaktelaConnector connector = loginBuilder().build();
        for (String[] next : new String[][]{{access2, refresh2}, {access3, "refresh-three"}, {access4, "refresh-four"}}) {
            reply(401, "");
            tokens(next[0], next[1]);
            ok();
            connector.get("tickets");
        }
        reply(400, "{\"error\":[\"" + access1 + "\", \"" + access2 + "\", \"" + access3 + "\"]}");

        DaktelaException e = assertThrows(DaktelaException.class, () -> connector.get("tickets"));

        assertEquals(List.of(access1, "***", "***"), e.getErrorData());
    }

    // --- Auth method and instance -----------------------------------------------------------

    @Test
    void loginAndRefreshCarryNoTokenWithQueryAuth() {
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        ok();

        loginBuilder().authMethod(AuthMethod.QUERY).build().get("tickets");

        assertNull(requests.get(0).query);
        assertEquals(List.of("accessToken=" + access1), requests.get(1).decodedParams());
        assertNull(requests.get(2).query);
        assertEquals(List.of("accessToken=" + access2), requests.get(3).decodedParams());
    }

    @Test
    void loginAndRefreshUseThePathPrefix() {
        RecordingStore store = new RecordingStore(null);
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        ok();

        loginBuilder().instance(instance() + "/daktela").tokenStore(store).build().get("tickets");

        assertEquals(List.of("POST /daktela/api/v6/login.json", "GET /daktela/api/v6/tickets.json",
                "POST /daktela/api/v6/refreshAccessToken.json", "GET /daktela/api/v6/tickets.json"), paths());
        assertEquals("jirka@" + instance() + "/daktela", store.saved.get(0).getOwner());
    }

    // --- Token store edge cases -------------------------------------------------------------

    @Test
    void storedTokensWithoutOwnerAreIgnored() {
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(new RecordingStore(TokenSet.of(access1, refresh1, null))).build().get("tickets");

        assertEquals("POST /api/v6/login.json", paths().get(0));
    }

    @Test
    void storedTokensThatCannotBeSentAreIgnored() {
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(new RecordingStore(TokenSet.of("bad token", refresh1, "jirka@" + instance())))
                .build().get("tickets");

        assertEquals("POST /api/v6/login.json", paths().get(0));
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void storedAccessTokenNearItsExpiryIsRefreshedBeforeTheFirstRequest() {
        String expiring = jwt("a0", clock.instant().plusSeconds(30));
        RecordingStore store = new RecordingStore(TokenSet.of(expiring, refresh1, "jirka@" + instance()));
        tokens(access2, refresh2);
        ok();

        loginBuilder().tokenStore(store).build().get("tickets");

        assertEquals(List.of("POST /api/v6/refreshAccessToken.json", "GET /api/v6/tickets.json"), paths());
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void failingSaveDoesNotStopRefreshes() {
        TokenStore saveFails = new TokenStore() {
            @Override
            public TokenSet load() {
                return null;
            }

            @Override
            public void save(TokenSet tokens) {
                throw new IllegalStateException("cache down " + tokens.getAccessToken());
            }
        };
        tokens(access1, refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        ok();

        assertTrue(loginBuilder().tokenStore(saveFails).build().get("tickets").isSuccess());
        assertEquals(access2, lastRequest().token);
    }

    // --- Clock skew -------------------------------------------------------------------------

    @Test
    void skewedTokenIsStillRefreshedAfterAnUnauthorizedResponse() {
        tokens(jwt("a1", clock.instant().plusSeconds(30)), refresh1);
        reply(401, "");
        tokens(access2, refresh2);
        ok();

        assertTrue(loginBuilder().build().get("tickets").isSuccess());
        assertEquals(access2, lastRequest().token);
    }

    @Test
    void refreshedTokenThatLooksExpiredIsNotRefreshedBeforeEveryRequest() {
        tokens(access1, refresh1);
        reply(401, "");
        tokens(jwt("a2", clock.instant().plusSeconds(30)), refresh2);
        ok();
        ok();
        DaktelaConnector connector = loginBuilder().build();

        connector.get("tickets");
        connector.get("tickets");

        assertEquals(1, count("POST /api/v6/refreshAccessToken.json"));
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
