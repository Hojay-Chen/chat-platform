package com.luxera.companion.contracts.application;

import java.util.regex.Pattern;

/**
 * LAP v1 — matching for subscription filters.
 *
 * <p>Two wildcards, no more: {@code *} matches within one path segment, {@code **} matches
 * anything. A subscription on {@code game://session/*} therefore watches every game session but
 * not {@code game://session/1/board}. Deliberately not a full URI-template implementation —
 * subscriptions need "which resources do I care about", not parameter extraction.
 */
public final class ResourceUriPattern {

    private static final String REGEX_SPECIALS = "\\^$.|?+()[]{}";

    private ResourceUriPattern() {
    }

    /** A null, blank, {@code *} or {@code **} pattern matches everything. */
    public static boolean matches(String pattern, String uri) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern) || "**".equals(pattern)) {
            return true;
        }
        if (uri == null) {
            return false;
        }
        return Pattern.compile(toRegex(pattern)).matcher(uri).matches();
    }

    static String toRegex(String pattern) {
        StringBuilder out = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    out.append(".*");
                    i += 2;
                } else {
                    out.append("[^/]*");
                    i++;
                }
            } else {
                if (REGEX_SPECIALS.indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
                i++;
            }
        }
        return out.append('$').toString();
    }
}
