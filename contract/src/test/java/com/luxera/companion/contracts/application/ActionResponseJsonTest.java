package com.luxera.companion.contracts.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v1 — the action contract must survive the wire.
 *
 * <p>Three transports carry {@link ActionResponse} (REST, MCP, and the in-process runtime port)
 * and two of them go through JSON. If a field silently drops in serialisation the failure shows up
 * as an agent that cannot tell a denial from a success, so every field is asserted round-trip.
 */
class ActionResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void full_response_round_trips() throws Exception {
        JsonNode state = mapper.readTree("{\"board\":[0,0,0,0,1,0,0,0,0],\"turn\":\"O\"}");
        JsonNode result = mapper.readTree("{\"accepted\":true,\"position\":4}");
        JsonNode eventData = mapper.readTree("{\"type\":\"MOVE\",\"position\":4}");

        ResourceView resource = new ResourceView(
                "game://session/abc", "game.session", "com.luxera.tictactoe", "sess-1",
                state, 7L, Instant.parse("2026-09-12T10:15:30Z"), "board 是 9 格数组");
        ApplicationEvent event = new ApplicationEvent(
                "game://session/abc#move-4", "game.move", "com.luxera.tictactoe",
                "game://session/abc", Instant.parse("2026-09-12T10:15:30Z"), eventData);
        ActionResponse original = new ActionResponse(
                ActionStatus.SUCCESS, result, resource, List.of(event), null);

        String json = mapper.writeValueAsString(original);
        ActionResponse back = mapper.readValue(json, ActionResponse.class);

        assertEquals(ActionStatus.SUCCESS, back.status());
        assertEquals(result, back.result());
        assertNull(back.error());
        assertTrue(back.isSuccess());

        assertNotNull(back.resource());
        assertEquals("game://session/abc", back.resource().uri());
        assertEquals("game.session", back.resource().resourceType());
        assertEquals("com.luxera.tictactoe", back.resource().applicationId());
        assertEquals("sess-1", back.resource().sessionId());
        assertEquals(state, back.resource().state());
        assertEquals(7L, back.resource().version());
        assertEquals(Instant.parse("2026-09-12T10:15:30Z"), back.resource().updatedAt());
        assertEquals("board 是 9 格数组", back.resource().agentHint());

        assertEquals(1, back.events().size());
        ApplicationEvent backEvent = back.events().get(0);
        assertEquals("game://session/abc#move-4", backEvent.id());
        assertEquals("game.move", backEvent.type());
        assertEquals("com.luxera.tictactoe", backEvent.source());
        assertEquals("game://session/abc", backEvent.target());
        assertEquals(eventData, backEvent.data());
    }

    @Test
    void failure_response_keeps_its_code() throws Exception {
        ActionResponse original = ActionResponse.failure(
                ActionStatus.DENIED, "NOT_A_PARTICIPANT", "该 principal 不在这个会话里");

        ActionResponse back = mapper.readValue(mapper.writeValueAsString(original), ActionResponse.class);

        assertEquals(ActionStatus.DENIED, back.status());
        assertNull(back.result());
        assertNull(back.resource());
        assertTrue(back.events().isEmpty());
        assertEquals("NOT_A_PARTICIPANT", back.error().code());
        assertEquals("该 principal 不在这个会话里", back.error().message());
    }

    @Test
    void events_are_never_null() {
        // 调用方不该为了防御 null 而写分支; 空集合是有意义的信息("没有副作用")。
        ActionResponse response = new ActionResponse(ActionStatus.SUCCESS, null, null, null, null);
        assertNotNull(response.events());
        assertTrue(response.events().isEmpty());
    }

    @Test
    void action_status_covers_the_documented_http_mapping() {
        // 这张表是 REST/MCP/SDK/Port 共用的语义; 少一个就会在某个传输上退化成 "未知错误"。
        for (ActionStatus expected : List.of(
                ActionStatus.SUCCESS,
                ActionStatus.INVALID_ARGUMENT,
                ActionStatus.IDEMPOTENCY_KEY_REQUIRED,
                ActionStatus.DENIED,
                ActionStatus.NOT_FOUND,
                ActionStatus.STATE_CONFLICT,
                ActionStatus.IDEMPOTENCY_IN_PROGRESS,
                ActionStatus.IDEMPOTENCY_KEY_REUSED,
                ActionStatus.FAILED)) {
            assertNotNull(ActionStatus.valueOf(expected.name()));
        }
    }

    @Test
    void action_spec_carries_the_agent_hint() throws Exception {
        // agentHint 是"应用策略不进 DH"的载体: 加第二个游戏时 DH 不该学到任何新东西。
        ActionSpec spec = new ActionSpec(
                "game.make_move", "com.luxera.tictactoe", "game.play", "落一子",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.AWARE,
                mapper.readTree("{\"type\":\"object\",\"properties\":{\"position\":{\"type\":\"integer\"}}}"),
                "能连三就先连三");

        assertTrue(spec.requiresIdempotencyKey());
        assertEquals("能连三就先连三", spec.agentHint());
        assertEquals(1, spec.inputSchema().get("properties").size());
    }

    @Test
    void only_read_actions_skip_idempotency() {
        ActionSpec read = new ActionSpec("game.state", "com.luxera.tictactoe", "game.play", "读棋盘",
                PermissionLevel.READ, RiskLevel.NONE, AttentionPolicy.NONE, null, null);
        assertTrue(read.isRead());
        assertFalse(read.requiresIdempotencyKey());
    }
}
