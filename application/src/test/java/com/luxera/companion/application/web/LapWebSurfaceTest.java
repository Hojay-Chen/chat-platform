package com.luxera.companion.application.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.StubPrincipalTokenReader;
import com.luxera.companion.application.repository.ActionInvocationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 面 —— {@code /api/v1} 之下真人与 Agent 走的是<em>同一条</em>路。
 *
 * <p>单测里 {@link com.luxera.companion.application.action.ActionGateway} 是直接被调用的,
 * 于是有一整类问题它照不到: 幂等键到底有没有从请求头读进来、重放有没有真的回一个
 * {@code Idempotent-Replay} 头、HTTP 状态码与 {@code ActionStatus} 的映射在真实响应里对不对、
 * 冒号路由({@code /actions:execute})在 Spring MVC 里到底通不通。这些只有过一遍 MockMvc 才知道。
 *
 * <p>身份走 {@link StubPrincipalTokenReader} —— 测试模块里没有 kernel, 令牌读取被换成一个能造出
 * 任意 principal 的替身。这也让"换个 principal 再调一次"变得和换一个请求头一样便宜。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LapWebSurfaceTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String CAPABILITIES = "/api/v1/capabilities";
    private static final String EXECUTE = "/api/v1/actions:execute";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ActionInvocationRepository invocations;

    // ─────────────────────────── 发现 ───────────────────────────

    /** 发现链的三级都过 HTTP 走一遍: 能力 → 候选应用 → 动作。 */
    @Test
    void theDiscoveryChainIsAvailableOverHttp() throws Exception {
        mvc.perform(get(CAPABILITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.capabilityId=='game.play')]").exists());

        mvc.perform(get("/api/v1/capabilities/game.play/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='" + APP_ID + "')]").exists());

        mvc.perform(get("/api/v1/applications/" + APP_ID + "/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionId=='game.make_move')]").exists())
                // agentHint 一路带到传输层 —— 它是 LLM 在这条链上唯一能读到的"怎么做"
                .andExpect(jsonPath("$[?(@.actionId=='game.make_move')].agentHint").exists());
    }

    // ─────────────────────────── 打开 / 会话 / 订阅 ───────────────────────────

    /**
     * 打开应用 = 开一个会话, 而<b>安装这个动作已经不存在了</b>。
     *
     * <p>{@code $.installationId} 那条反向断言是刻意留的: 只断言"会话开出来了"的话, 哪天有人
     * 把 {@code /install} 加回来(哪怕只是顺手), 这个类照样绿。加上"没有安装 id 这个东西",
     * 加回来的那一刻就红。
     *
     * <p>R11 起响应是<b>§16 的形状</b>: {@code application} 与 {@code participant} 各自
     * 嵌套着给出 —— 它们不是两个孤立的字符串, 而是"哪份软件的哪一版"与"你在这场里是谁"
     * 两份声明。
     */
    @Test
    void openingAnApplicationStartsASessionAndMintsNoInstallation() throws Exception {
        String alice = principalId();

        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.id").value(APP_ID))
                .andExpect(jsonPath("$.application.version").value("1.0.0"))
                .andExpect(jsonPath("$.participant.principalType").value("HUMAN"))
                .andExpect(jsonPath("$.participant.principalId").value(alice))
                .andExpect(jsonPath("$.participant.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.participantCount").value(1))
                .andExpect(jsonPath("$.installationId").doesNotExist())
                // v1 的 ownerPrincipal* 从响应里消失了: 主人只是 role=OWNER 的那一个参与者,
                // 会话行上的 owner 是"出处"不是"权柄" —— 见 SessionResponse 的注释。
                .andExpect(jsonPath("$.ownerPrincipalId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertEquals(36, objectMapper.readTree(body).path("sessionId").asText().length(),
                "开出来的这个会话 id 就是调用方下一步下动作要用的那个");
        assertEquals(36, objectMapper.readTree(body).path("participant").path("id").asText().length(),
                "participant.id 是参与者行的 id, 不是 principal id");
    }

    /**
     * §97 的应用详情 —— 应用市场与 SurfaceHost 的全部输入。
     *
     * <p>三样东西必须同时在场, 否则客户端只能自己去拼: 十态原值({@code status})、
     * 五态投影({@code availability} 三列)、以及 ui 计划({@code ui} 五态 surface)。
     */
    @Test
    void theApplicationDetailCarriesStatusAvailabilityAndSurfaces() throws Exception {
        mvc.perform(get("/api/v1/applications/" + APP_ID)
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(APP_ID))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.availability.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.availability.inMarket").value(true))
                .andExpect(jsonPath("$.availability.allowsNewSession").value(true))
                .andExpect(jsonPath("$.availability.allowsExistingSession").value(true))
                .andExpect(jsonPath("$.ui.type").value("EMBEDDED"))
                .andExpect(jsonPath("$.ui.surfaces.length()").value(5))
                .andExpect(jsonPath("$.ui.surfaces[0].entry")
                        .value("/applications/{applicationId}/sessions/{sessionId}"));
    }

    /** 没注册过的应用是 404, 而不是一份空壳详情。 */
    @Test
    void anUnregisteredApplicationHasNoDetailPage() throws Exception {
        mvc.perform(get("/api/v1/applications/com.luxera.nope")
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_APPLICATION"));
    }

    /**
     * 权限全是 v1 的 {@code /install} 那两件事的继任者。
     *
     * <ul>
     *   <li>{@code /install} 这个端点本身必须 404 —— 原则 1: Application 不需要用户安装。
     *       加回任何一个"安装/启用/激活"入口, 这条立刻红。</li>
     *   <li>能力不再由请求体里的 {@code capabilities} 数组决定("省略就等于全给"这种默认值
     *       是没法解释的), 而由<b>角色</b>展开: 打开者成为 OWNER, 拿到该应用声明的全部能力。</li>
     * </ul>
     */
    @Test
    void installingIsNoLongerAnEndpointAndOpeningGrantsTheDeclaredCapabilities() throws Exception {
        String alice = principalId();

        mvc.perform(post("/api/v1/applications/" + APP_ID + "/install")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilities\":[\"game.play\"]}"))
                .andExpect(status().isNotFound());

        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(objectMapper.readTree(body).path("capabilities").toString().contains("game.play"),
                "打开者按角色拿到这个应用声明的能力: " + body);
    }

    /**
     * 会话开得出、列得到、结束得了 —— Agent 这条身份走的是同一个控制器。
     *
     * <p>这里刻意用 {@code companionId} 过滤器查回来: 它不是身份(身份来自令牌), 但它必须
     * 真的把那一条筛出来, 否则"这个数字人在用哪些应用"这类查询会悄悄退化成"全部返回"。
     */
    @Test
    void sessionsCanBeOpenedListedAndEnded() throws Exception {
        String companionId = "dh-" + UUID.randomUUID();

        String sessionId = openSession(companionId, PrincipalType.AGENT);

        mvc.perform(get("/api/v1/sessions").param("companionId", companionId)
                        .header("Authorization", bearer(companionId, PrincipalType.AGENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionId + "')]").exists());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/sessions/" + sessionId)
                        .header("Authorization", bearer(companionId, PrincipalType.AGENT)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", bearer(companionId, PrincipalType.AGENT))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", "game://session/" + sessionId, "{}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_ENDED"));
    }

    @Test
    void subscriptionsCanBeCreatedListedAndRevoked() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice, PrincipalType.HUMAN);

        String body = mvc.perform(post("/api/v1/subscriptions")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","resourceUriPattern":"game://session/%s",
                                 "eventTypes":["game.move","game.finish"],"deliveryMode":"SINK"}"""
                                .formatted(sessionId, sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.deliveryMode").value("SINK"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String subscriptionId = objectMapper.readTree(body).path("subscriptionId").asText();

        mvc.perform(get("/api/v1/subscriptions").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.subscriptionId=='" + subscriptionId + "')]").exists());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/subscriptions/" + subscriptionId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
    }

    // ─────────────────────────── 动作 ───────────────────────────

    @Test
    void aWholeGameCanBePlayedOverHttp() throws Exception {
        String alice = principalId();
        String sessionId = openSession(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + sessionId;

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.resource.uri").value(uri))
                .andExpect(jsonPath("$.resource.version").value(1));

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", "move-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", uri, "{\"position\":4}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.state.board[4]").value("X"));

        // 读动作没有键也能走, 而且读到的必须是<b>刚下完</b>的棋盘
        mvc.perform(get("/api/v1/resources").param("uri", uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state.board[4]").value("X"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    /** Canonical 与 Alias 必须是同一条路 —— 不是"也能通", 是行为逐字节相同。 */
    @Test
    void theAliasRouteBehavesExactlyLikeTheCanonicalOne() throws Exception {
        String body = executeBody("game.teleport", "game://session/" + UUID.randomUUID(), "{}");

        String canonical = mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(principalId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTION_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        String alias = mvc.perform(post("/api/v1/actions/execute")
                        .header("Authorization", bearer(principalId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(objectMapper.readTree(canonical).path("error").path("code"),
                objectMapper.readTree(alias).path("error").path("code"),
                "两条路由必须映射到同一个方法, 而不是各写一份");
    }

    // ─────────────────────────── 幂等 ───────────────────────────

    @Test
    void replayingWithTheSameKeyReturnsTheSameBytesAndReplayHeader() throws Exception {
        String alice = principalId();
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);
        String key = "create-" + UUID.randomUUID();
        String raw = executeBody("game.create", uri, "{}");

        String first = mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn().getResponse().getContentAsString();

        long before = invocationCount(alice);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(content().string(first));

        assertEquals(before, invocationCount(alice), "重放不该再落一行");
    }

    @Test
    void reusingAKeyWithADifferentPayloadIs422() throws Exception {
        String alice = principalId();
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);
        String key = "create-" + UUID.randomUUID();

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isOk());

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{\"opponentPrincipalId\":\"x\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void writingWithoutAnIdempotencyKeyIs400() throws Exception {
        String alice = principalId();
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ─────────────────────────── 参数与身份 ───────────────────────────

    @Test
    void anUnknownActionIs404AndAMissingTargetIs400() throws Exception {
        String alice = principalId();

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.teleport", "game://session/x", "{}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTION_NOT_FOUND"));

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", null, "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TARGET_REQUIRED"));
    }

    /** 没有身份的请求 401, 而不是被当成某个默认的真人 —— 那正是 Principal 统一要防的事。 */
    @Test
    void anUnidentifiedCallerIs401() throws Exception {
        mvc.perform(post(EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", "game://session/x", "{}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.error.code").value("UNIDENTIFIED_PRINCIPAL"));
    }

    /**
     * 不在这一局里的人调动作 → 403 {@code NOT_A_PARTICIPANT}。
     *
     * <p>注意这里<em>借用了别人的会话 URI</em>: 会话解析排在权限判定之前, 所以拿一个不存在的
     * 会话 id 过来会先撞上 404 UNKNOWN_SESSION, 测不到权限那条。这不是巧合 —— 顺序是
     * "解析目标 → 判权限", 于是不在场者拿随机 UUID 试探时得到的是 404 而非 403。
     *
     * <p>v1 这里问的是"装没装过"(一次永久的授权), v2 问的是"在不在这局里"(一次会话内的加入)。
     * 位置、状态码、断言形状都没变 —— 变的正是这次重构要换掉的那一个问题。
     */
    @Test
    void aCallerOutsideTheSessionIs403() throws Exception {
        String alice = principalId();
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(principalId()))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_PARTICIPANT"));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 开一个会话并返回它的 id —— <b>这里就是 v1 那个 {@code install()} 夹具的位置</b>。
     *
     * <p>它少了一步: v1 要"先装再开", 两个请求两行状态; v2 一个请求就是一件事。夹具的这一处
     * 缩短, 正是整个 R9 想说的话。
     */
    private String openSession(String principalId, PrincipalType type) throws Exception {
        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", bearer(principalId, type))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("sessionId").asText();
    }

    private String executeBody(String action, String target, String inputJson) {
        return """
                {"action":"%s","target":%s,"input":%s}"""
                .formatted(action,
                        target == null ? "null" : "\"" + target + "\"",
                        inputJson);
    }

    private long invocationCount(String principalId) {
        return invocations.findAll().stream()
                .filter(r -> principalId.equals(r.getPrincipalId()))
                .count();
    }

    private static String principalId() {
        return UUID.randomUUID().toString();
    }

    private static String bearer(String principalId) {
        return bearer(principalId, PrincipalType.HUMAN);
    }

    private static String bearer(String principalId, PrincipalType type) {
        return StubPrincipalTokenReader.bearer(type, principalId);
    }
}
