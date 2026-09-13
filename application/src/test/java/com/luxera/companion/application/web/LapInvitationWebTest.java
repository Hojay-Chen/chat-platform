package com.luxera.companion.application.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.StubPrincipalTokenReader;
import com.luxera.companion.application.repository.SessionInvitationRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LAP v2: <b>邀请与加入的网络面</b> —— 分享链接从铸造到兑票, 一整条 HTTP 链路。
 *
 * <p>{@code InvitationController} 是 R10 里唯一<em>全新</em>的控制器(Session / Participant 两个
 * 在 R9 就有了), 所以这一整轮的 HTTP 面测试几乎都押在它上面。R10 计划的
 * {@code SessionJoinTest}/{@code SessionLeaveTest} 也落在这里: HTTP 面上"加入"只有兑票这一条新路
 * ("自己加入"在 R9 已测, 见 {@code LapWebSurfaceTest}), "离开"则要验证<b>先经邀请进来、再自己退
 * 出去</b>的完整往返, 而不是各测各的。
 *
 * <p>关键是 {@code /join/{token}} 这个公开端点: 手里只有一段 token 的陌生人, 不经过任何会话内
 * 资格即可加入 —— 这就是 §14 说的 Capability Token 的唯一入口。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LapInvitationWebTest {

    private static final String APP_ID = "com.luxera.tictactoe";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SessionInvitationRepository invitations;

    @Autowired
    SessionParticipantRepository participants;

    // ─────────────────────────── SessionJoinTest: 通过邀请加入 ───────────────────────────

    @Test
    void aShareLinkMintsAndThenJoinsAStranger() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);

        String mint = mvc.perform(post("/api/v1/sessions/" + sessionId + "/invitations")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OBSERVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.joinUrl").isNotEmpty())
                .andExpect(jsonPath("$.role").value("OBSERVER"))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(mint).path("token").asText();
        String id = "p-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/join/" + token)
                        .header("Authorization", bearer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.role").value("OBSERVER"));
    }

    @Test
    void aUsedTokenIsRejected409() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);

        // 一次性票: 第一个人兑掉就 CONSUMED, 第二个人再拿同一张票必被拒。
        String token = tokenField(mint(sessionId, alice, "{\"maxUses\":1}"));

        mvc.perform(post("/api/v1/join/" + token)
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/join/" + token)
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isConflict());
    }

    @Test
    void aStrangerWithoutAValidTokenCannotJoinAnInviteOnlySession() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);   // 默认 JOIN_INVITE_ONLY

        mvc.perform(post("/api/v1/sessions/" + sessionId + "/participants")
                        .header("Authorization", bearer(principalId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SESSION_INVITE_ONLY"));
    }

    /** token 明文的唯一出口是 mint 响应的 {@code token} 字段; 库里一行都不含它。 */
    @Test
    void thePlaintextTokenNeverReachesTheDatabase() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);

        String token = tokenField(mint(sessionId, alice, "{}"));

        long rowsHoldingPlaintext = invitations.findBySessionId(sessionId).stream()
                .filter(r -> token.equals(r.getTokenHash()))
                .count();
        assertEquals(0, rowsHoldingPlaintext, "任何一列都不该装着 token 明文");

        // 反向: 表里存的 token_hash 是 64 字符哈希, 不是 30 字符 token。
        assertTrue(invitations.findBySessionId(sessionId).stream()
                .allMatch(r -> r.getTokenHash().length() == 64));
    }

    @Test
    void aMalformedTokenIs404Shape() throws Exception {
        mvc.perform(post("/api/v1/join/not-a-real-token")
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_INVITATION"));
    }

    // ─────────────────────────── SessionLeaveTest: 进来之后再出去 ───────────────────────────

    @Test
    void anInvitedParticipantCanLeaveAndRejoinWithANewTicket() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);

        String bob = principalId();
        String token1 = tokenField(mint(sessionId, alice, "{}"));

        mvc.perform(post("/api/v1/join/" + token1).header("Authorization", bearer(bob)))
                .andExpect(status().isOk());

        // 自己走
        mvc.perform(delete("/api/v1/sessions/" + sessionId + "/participants/me")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());

        // 第一张票烧掉了(bob 用它进来过), 主人再发一张新的, bob 又能回来。
        String token2 = tokenField(mint(sessionId, alice, "{}"));
        mvc.perform(post("/api/v1/join/" + token2).header("Authorization", bearer(bob)))
                .andExpect(status().isOk());

        assertEquals(1, participants.findBySessionId(sessionId).stream()
                .filter(p -> bob.equals(p.getPrincipalId()))
                .count(), "来来去去仍是同一行参与者记录");
    }

    // ─────────────────────────── 定向邀请: 事件与目标 ───────────────────────────

    @Test
    void aDirectedInvitationTargetsTheNamedCompanion() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice);
        String dh = "dh-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/sessions/" + sessionId + "/invitations")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"AGENT\",\"targetId\":\"" + dh + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("AGENT"))
                .andExpect(jsonPath("$.targetId").value(dh));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private String openSession(String principalId) throws Exception {
        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", bearer(principalId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("sessionId").asText();
    }

    private String mint(String sessionId, String actor, String body) throws Exception {
        return mvc.perform(post("/api/v1/sessions/" + sessionId + "/invitations")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String tokenField(String mintResponseJson) throws Exception {
        return objectMapper.readTree(mintResponseJson).path("token").asText();
    }

    private static String principalId() {
        return UUID.randomUUID().toString();
    }

    private static String bearer(String principalId) {
        return StubPrincipalTokenReader.bearer(PrincipalType.HUMAN, principalId);
    }
}