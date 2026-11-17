package com.daktela.connector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces secrets (access tokens, refresh tokens, a login password) in text that the server sent
 * back, so that they do not end up in exception messages and logs. Each secret is matched as is,
 * URL-encoded and JSON-escaped, as a server may echo it in any of these forms.
 */
final class Redactor {

    static final String MASK = "***";

    private static final Redactor NONE = new Redactor(List.of());

    private final List<String> forms;

    private Redactor(List<String> forms) {
        this.forms = forms;
    }

    static Redactor of(Collection<String> secrets) {
        Set<String> forms = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            String json = secret.replace("\\", "\\\\").replace("\"", "\\\"");
            forms.add(secret);
            forms.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            forms.add(percentEncode(secret));
            // PHP's json_encode, which the server uses, also escapes the slash and writes non-ASCII
            // characters as \\uXXXX.
            for (String escaped : List.of(json, json.replace("/", "\\/"))) {
                forms.add(escaped);
                forms.add(escapeNonAscii(escaped));
            }
        }
        if (forms.isEmpty()) {
            return NONE;
        }
        List<String> sorted = new ArrayList<>(forms);
        // Longer forms first, so that a secret containing another one is replaced whole.
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return new Redactor(List.copyOf(sorted));
    }

    String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String form : forms) {
            result = result.replace(form, MASK);
        }
        return result;
    }

    /**
     * Returns a copy of parsed JSON (maps, lists, strings) with the secrets replaced in strings and
     * map keys.
     */
    Object redactJson(Object value) {
        if (forms.isEmpty()) {
            return value;
        }
        if (value instanceof String) {
            return redact((String) value);
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(redactJson(entry.getKey()), redactJson(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(redactJson(item));
            }
            return copy;
        }
        return value;
    }

    private static String escapeNonAscii(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c < 0x80) {
                escaped.append(c);
            } else {
                escaped.append(String.format("\\u%04x", (int) c));
            }
        }
        return escaped.toString();
    }

    /** RFC 3986 encoding: everything except unreserved characters is percent-encoded. */
    private static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }
}
