package com.daktela.connector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Runs a local HTTP server that answers with queued replies and records every request, to pin
 * down the exact wire format and response handling.
 */
abstract class HttpTestSupport {

    /** Status of a reply that closes the connection without sending a response. */
    static final int CLOSE_CONNECTION = -1;

    HttpServer server;
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    final List<Duration> sleeps = new ArrayList<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = in.readAllBytes();
            }
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("X-AUTH-TOKEN"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("User-Agent"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(requestBody, StandardCharsets.UTF_8)));
            Reply reply = replies.isEmpty() ? new Reply(200, "{\"error\":[],\"result\":{\"data\":[],\"total\":0}}") : replies.poll();
            if (reply.status == CLOSE_CONNECTION) {
                exchange.close();
                return;
            }
            reply.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    String instance() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    DaktelaConnector.Builder builder() {
        return DaktelaConnector.builder()
                .instance(instance())
                .accessToken("secret-token")
                .timeout(Duration.ofSeconds(5))
                .sleeper(sleeps::add)
                .clock(clock);
    }

    DaktelaConnector connector() {
        return builder().build();
    }

    void reply(int status, String body) {
        replies.add(new Reply(status, body));
    }

    Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    /** An unsigned JWT whose {@code exp} claim is the given time. */
    static String jwt(String id, Instant expiresAt) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"u\":\"" + id + "\",\"exp\":" + expiresAt.getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".c2ln";
    }

    static final class Reply {
        final int status;
        final String body;
        final Map<String, String> headers;

        Reply(int status, String body) {
            this(status, body, Map.of());
        }

        Reply(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    static final class Recorded {
        final String method;
        final String path;
        final String query;
        final String token;
        final String contentType;
        final String userAgent;
        final String authorization;
        final String body;

        Recorded(String method, String path, String query, String token, String contentType, String userAgent,
                 String authorization, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.token = token;
            this.contentType = contentType;
            this.userAgent = userAgent;
            this.authorization = authorization;
            this.body = body;
        }

        List<String> decodedParams() {
            if (query == null || query.isEmpty()) {
                return List.of();
            }
            List<String> params = new ArrayList<>();
            for (String pair : Arrays.asList(query.split("&"))) {
                int eq = pair.indexOf('=');
                params.add(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8) + "="
                        + URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            return params;
        }
    }

    /** A clock the test moves forward by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
