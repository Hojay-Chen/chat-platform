package com.luxera.companion.contracts.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceUriPatternTest {

    @Test
    void single_star_stays_within_one_segment() {
        assertTrue(ResourceUriPattern.matches("game://session/*", "game://session/abc"));
        // 关键区分: * 不应该跨段, 否则订阅 "所有棋局" 会连棋局内部资源一起收,
        // 事件量会随应用内部结构爆炸。
        assertFalse(ResourceUriPattern.matches("game://session/*", "game://session/abc/board"));
    }

    @Test
    void double_star_crosses_segments() {
        assertTrue(ResourceUriPattern.matches("game://**", "game://session/abc/board"));
    }

    @Test
    void blank_or_star_pattern_matches_everything() {
        assertTrue(ResourceUriPattern.matches(null, "game://session/abc"));
        assertTrue(ResourceUriPattern.matches("", "game://session/abc"));
        assertTrue(ResourceUriPattern.matches("*", "anything://at/all"));
    }

    @Test
    void null_uri_never_matches_a_real_pattern() {
        assertFalse(ResourceUriPattern.matches("game://session/*", null));
    }

    @Test
    void regex_metacharacters_in_the_pattern_are_literal() {
        // URI 里有 . 和 : , 不能当正则元字符解释, 否则 "game.play" 会匹配 "gameXplay"。
        assertTrue(ResourceUriPattern.matches("game.play://a", "game.play://a"));
        assertFalse(ResourceUriPattern.matches("game.play://a", "gameXplay://a"));
    }

    @Test
    void subscription_matches_uri_and_type_together() {
        SubscriptionRequest all = new SubscriptionRequest("game://session/*", List.of(), null, null);
        assertTrue(all.matches("game://session/abc", "game.move"));
        assertFalse(all.matches("reminder://item/1", "game.move"));

        SubscriptionRequest movesOnly = new SubscriptionRequest(
                "game://session/*", List.of("game.move", "game.finish"), null, null);
        assertTrue(movesOnly.matches("game://session/abc", "game.move"));
        assertFalse(movesOnly.matches("game://session/abc", "game.chat"));
    }

    @Test
    void delivery_mode_defaults_to_sink() {
        SubscriptionRequest request = new SubscriptionRequest("game://**", null, null, null);
        assertTrue(SubscriptionRequest.MODE_SINK.equals(request.deliveryMode()));
        assertTrue(request.eventTypes().isEmpty());
    }
}
