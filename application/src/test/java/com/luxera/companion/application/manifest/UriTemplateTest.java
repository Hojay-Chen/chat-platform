package com.luxera.companion.application.manifest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资源归属判定的地基: 网关靠它回答"这个 target 属于哪个应用", 幂等与 CAS 都建在这个答案上。
 * 判错的后果是"落子落进了别人的棋盘"。
 */
class UriTemplateTest {

    @Test
    void capturesSingleSegmentVariable() {
        assertEquals("abc123", UriTemplate.variable("game://session/{sessionId}",
                "game://session/abc123", "sessionId").orElseThrow());
    }

    @Test
    void doesNotMatchDifferentSchemeOrHost() {
        assertFalse(UriTemplate.matches("game://session/{sessionId}", "gomoku://match/1"));
        assertFalse(UriTemplate.matches("game://session/{sessionId}", "game://match/1"));
        assertFalse(UriTemplate.matches("game://session/{sessionId}", "game://session"));
    }

    @Test
    void variableDoesNotMatchAnEmptySegment() {
        assertFalse(UriTemplate.matches("game://session/{sessionId}", "game://session/"));
    }

    @Test
    void doesNotMatchExtraSegments() {
        assertFalse(UriTemplate.matches("game://session/{sessionId}", "game://session/1/board"));
    }

    @Test
    void doubleStarSwallowsTheRest() {
        assertTrue(UriTemplate.matches("game://**", "game://session/1/board"));
        assertTrue(UriTemplate.matches("reminder://**", "reminder://item/42"));
        assertFalse(UriTemplate.matches("game://**", "gomoku://match/1"));
    }

    @Test
    void singleStarMatchesExactlyOneSegment() {
        assertTrue(UriTemplate.matches("game://session/*", "game://session/1"));
        assertFalse(UriTemplate.matches("game://session/*", "game://session/1/board"));
    }

    @Test
    void literalTemplateMatchesItself() {
        assertTrue(UriTemplate.matches("reminder://pending", "reminder://pending"));
        assertFalse(UriTemplate.matches("reminder://pending", "reminder://pending/1"));
    }

    @Test
    void nullsNeverMatch() {
        assertFalse(UriTemplate.matches(null, "game://session/1"));
        assertFalse(UriTemplate.matches("game://session/{id}", null));
    }
}
