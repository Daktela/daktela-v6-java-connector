package com.daktela.connector;

import com.daktela.connector.query.DaktelaFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DaktelaConnectorConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "my.daktela.com",
            "https://my.daktela.com",
            "https://my.daktela.com/",
            "HTTPS://my.daktela.com",
            "  my.daktela.com  ",
            "https://my.daktela.com/api/v6/"
    })
    void instanceVariantsNormalizeToHttpsBaseUrl(String instance) {
        assertEquals("https://my.daktela.com", DaktelaConnector.normalizeBaseUrl(instance));
    }

    @Test
    void instanceWithPortIsKept() {
        assertEquals("https://my.daktela.com:8443", DaktelaConnector.normalizeBaseUrl("my.daktela.com:8443"));
    }

    @Test
    void plainHttpIsAllowedForLocalhostOnly() {
        assertEquals("http://127.0.0.1:8080", DaktelaConnector.normalizeBaseUrl("http://127.0.0.1:8080"));
        assertEquals("http://localhost", DaktelaConnector.normalizeBaseUrl("http://localhost"));
        assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.normalizeBaseUrl("http://my.daktela.com"));
        assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.normalizeBaseUrl("http://127.example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://",
            "https:///",
            "ftp://my.daktela.com",
            "my.daktela.com?x=1",
            "my.daktela.com#frag",
            "https://user:pass@my.daktela.com",
            "https://my.daktela.com/some/path",
            "not a host"
    })
    void invalidInstancesAreRejected(String instance) {
        assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.normalizeBaseUrl(instance));
    }

    @Test
    void invalidInstanceErrorDoesNotEchoCredentials() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DaktelaConnector.normalizeBaseUrl("https://user:hunter2@my.daktela.com"));

        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    @Test
    void tokenWithTrailingNewlineIsRejectedWithoutLeakingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DaktelaConnector.builder()
                .instance("my.daktela.com")
                .accessToken("s3cr3t-token\n")
                .build());

        assertFalse(e.getMessage().contains("s3cr3t"), e.getMessage());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> base().timeout(Duration.ZERO).build());
        assertThrows(NullPointerException.class, () -> base().timeout(null).build());
        assertThrows(IllegalArgumentException.class, () -> base().maxRetries(-1).build());
        assertThrows(IllegalArgumentException.class, () -> base().accessToken(" ").build());
        assertThrows(NullPointerException.class, () -> base().authMethod(null).build());
    }

    @Test
    void retryAfterParsesSecondsAndHttpDates() {
        assertEquals(Duration.ofSeconds(7), DaktelaConnector.parseRetryAfter("7"));
        assertNull(DaktelaConnector.parseRetryAfter(null));
        assertNull(DaktelaConnector.parseRetryAfter("soon"));

        String inTwoMinutes = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusMinutes(2));
        Duration delay = DaktelaConnector.parseRetryAfter(inTwoMinutes);
        assertTrue(delay.compareTo(Duration.ofSeconds(100)) > 0 && delay.compareTo(Duration.ofSeconds(121)) <= 0,
                delay.toString());

        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusMinutes(2));
        assertEquals(Duration.ZERO, DaktelaConnector.parseRetryAfter(past));
    }

    @Test
    void nullEqualityBecomesNullCheck() {
        assertEquals("isnull", DaktelaFilter.eq("user", null).getOperator());
        assertEquals("isnotnull", DaktelaFilter.neq("user", null).getOperator());
    }

    @Test
    void emptyInListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DaktelaFilter.in("stage", List.of()));
        assertThrows(IllegalArgumentException.class, () -> DaktelaFilter.notIn("stage"));
    }

    private static DaktelaConnector.Builder base() {
        return DaktelaConnector.builder().instance("my.daktela.com").accessToken("token");
    }
}
