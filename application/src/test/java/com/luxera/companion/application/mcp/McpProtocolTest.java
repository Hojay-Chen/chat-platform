package com.luxera.companion.application.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.StubPrincipalTokenReader;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.manifest.RuntimeType;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * LAP v1 §MCP: 适配器只做两件事, 这个类就钉这两件事, 以及它<em>没</em>做的那一件。
 *
 * <p>最要紧的一条断言是<b>"没有创建 ApplicationSession"</b>。MCP 有自己的会话概念, 它和
 * {@code ApplicationSession} 长得像、名字像, 而客户端的每一次握手都确实"开了一个会话" ——
 * 所以把它们接起来是极自然的下一步, 也正是必须挡住的下一步: {@code application_session} 是
 * 归属链 {@code Application → ApplicationSession → Resource} 的一环, 每一个 MCP 客户端的握手
 * 都往里塞一行, 那行就既不属于任何人、也不指向任何资源。这个类用 {@code sessions.count()} 的
 * 前后对比把这条钉住。
 *
 * <p>其次要紧的是"下游走的是同一个网关": 断言不写成"tools/call 返回了 200", 而是写成
 * <b>"真人从 REST 读同一个 URI, 看到的是 MCP 客户端刚写下的那一手"</b>。前者在适配器自己伪造
 * 一个响应时也会绿, 后者不会。
 *
 * <p>MCP 没有 JWT, 身份靠 {@code X-Mcp-Principal} + 服务密钥(测试专用值见
 * {@code application-test.yml}); 真人那一侧仍走 {@link StubPrincipalTokenReader} 造的令牌。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class McpProtocolTest {

    private static final String MCP = "/mcp";
    private static final String KEY = "test-mcp-service-key";
    private static final String APP_ID = "com.luxera.tictactoe";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    McpToolCatalog catalogue;

    @Autowired
    McpSessions protocolSessions;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ApplicationSessionRepository sessions;

    // ─────────────────────────── 握手 ───────────────────────────

    @Test
    void initializeNegotiatesAProtocolSessionAndWritesNothingToTheDatabase() throws Exception {
        long before = sessions.count();

        String sessionId = mcp("agent-init", """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":
                 {"protocolVersion":"2025-06-18","clientInfo":{"name":"t","version":"1"}}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("luxera-lap"))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andReturn().getResponse().getHeader("Mcp-Session-Id");

        assertTrue(sessionId != null && !sessionId.isBlank(),
                "握手必须回一个 Mcp-Session-Id —— 那是协议会话的句柄");
        assertTrue(protocolSessions.find(sessionId).isPresent(),
                "会话要活在本进程里(McpSessions), 而不是任何一张表里");
        assertEquals(before, sessions.count(),
                "MCP 握手开了个协议会话, 但绝不能开 ApplicationSession");
    }

    /** 客户端报一版我们不认识的, 服务端报自己最新的那版(规范: 由客户端决定要不要继续)。 */
    @Test
    void anUnknownProtocolVersionFallsBackToOurs() throws Exception {
        mcp("agent-ver", rpc(1, "initialize", "{\"protocolVersion\":\"1999-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value(McpSessions.PROTOCOL_VERSION));
    }

    @Test
    void pingIsAnsweredWithAnEmptyResult() throws Exception {
        mcp("agent-ping", rpc(7, "ping", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());
    }

    /** 通知不回应 —— 连"方法不认识"的通知也只回 202, 因为客户端按规范根本不会读那个错误对象。 */
    @Test
    void notificationsGet202AndAnEmptyBody() throws Exception {
        mcp("agent-note", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        mcp("agent-note", "{\"jsonrpc\":\"2.0\",\"method\":\"resources/list\"}")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void anUnknownMethodIsMethodNotFound() throws Exception {
        mcp("agent-method", rpc(3, "resources/list", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.METHOD_NOT_FOUND))
                .andExpect(jsonPath("$.error.data.code").value("METHOD_NOT_FOUND"));
    }

    @Test
    void aBodyThatIsNotJsonRpcIs400() throws Exception {
        mcp("agent-junk", "不是 JSON")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.PARSE_ERROR));

        mcp("agent-junk", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.INVALID_REQUEST));

        // 批量数组: 本适配器只收单条, 不假装支持
        mcp("agent-junk", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.INVALID_REQUEST));
    }

    // ─────────────────────────── 身份 ───────────────────────────

    /**
     * 四个身份场景。共同点是它们<b>都没走到 {@code tools/list}</b> —— 一个身份说不清楚的
     * MCP 请求连工具目录都拿不到。
     */
    @Test
    void identityProblemsAreRejectedBeforeAnythingElseHappens() throws Exception {
        // 什么都没有: 三个解析器都不认 → 401(凭据没给对)
        mvc.perform(post(MCP).contentType(MediaType.APPLICATION_JSON)
                        .content(rpc(1, "tools/list", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.UNAUTHORIZED))
                .andExpect(jsonPath("$.error.data.code").value("UNIDENTIFIED_PRINCIPAL"));

        // 服务密钥不对 → 401
        mvc.perform(post(MCP)
                        .header("X-Mcp-Principal", "AGENT:someone")
                        .header("X-Mcp-Service-Key", "猜的")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc(1, "tools/list", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.data.code").value("MCP_UNAUTHORIZED"));

        // 声称自己是真人 → 403: 真人身份只能由 JWT 承载, 再试也不会好
        mvc.perform(post(MCP)
                        .header("X-Mcp-Principal", "HUMAN:haojie")
                        .header("X-Mcp-Service-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc(1, "tools/list", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.data.code").value("MCP_PRINCIPAL_INVALID"));

        // 类型不认识 → 403, 同属"改调用方代码"那一类
        mvc.perform(post(MCP)
                        .header("X-Mcp-Principal", "ROBOT:x")
                        .header("X-Mcp-Service-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc(1, "tools/list", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.data.code").value("MCP_PRINCIPAL_INVALID"));
    }

    @Test
    void aSessionFromAnotherPrincipalOrAVanishedOneIsRejected() throws Exception {
        String sessionId = mcp("agent-owner", rpc(1, "initialize", "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Mcp-Session-Id");

        mcpWithSession("agent-thief", rpc(2, "tools/list", null), sessionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.SESSION_PRINCIPAL_MISMATCH));

        mcpWithSession("agent-owner", rpc(2, "tools/list", null), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.SESSION_NOT_FOUND));

        // 本人的会话照常可用 —— 否则上面两条断言只是在证明"带了会话头就失败"
        mcpWithSession("agent-owner", rpc(2, "tools/list", null), sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").exists());
    }

    // ─────────────────────────── 发现 ───────────────────────────

    @Test
    void toolsListExposesTheActionCatalogue() throws Exception {
        String body = mcp("agent-list", rpc(1, "tools/list", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 调用方没有安装任何应用 —— 目录照样全给。发现不是授权。
        JsonNode move = toolNamed(body, "tictactoe.game_make_move");
        assertTrue(move.isObject(), "tools/list 里必须有 tictactoe.game_make_move");
        assertTrue(move.path("description").asText().contains("game://session/{sessionId}"),
                "description 必须写明 target 长什么样, 否则这个工具没法被调用");
        assertTrue(move.path("description").asText().contains("格子序号"),
                "agentHint 要一路带到工具描述里 —— 策略提示住在应用里, 不在数字人里");
        assertEquals("object", move.path("inputSchema").path("type").asText());
        assertEquals("integer",
                move.path("inputSchema").path("properties").path("position").path("type").asText());
        assertEquals("string",
                move.path("inputSchema").path("properties").path("target").path("type").asText());

        List<String> required = new ArrayList<>();
        move.path("inputSchema").path("required").forEach(name -> required.add(name.asText()));
        assertTrue(required.contains("target"), "target 是平台级必填项");
        assertTrue(required.contains("position"), "动作自己的必填项必须原样保留");

        assertTrue(move.path("annotations").path("readOnlyHint").isBoolean(),
                "annotations 是给客户端的提示, 缺了会让客户端把写动作当读动作缓存");
    }

    /** 同名能力下的两个应用各有各的工具 —— 它们的动作 id 是一样的, 工具名不能也一样。 */
    @Test
    void twoApplicationsDeclaringTheSameActionGetTwoTools() throws Exception {
        String body = mcp("agent-two", rpc(1, "tools/list", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(toolNamed(body, "tictactoe.game_make_move").isObject());
        assertTrue(toolNamed(body, "gomoku.game_make_move").isObject());

        assertEquals("com.luxera.tictactoe",
                catalogue.find("tictactoe.game_make_move").orElseThrow().applicationId());
        assertEquals("com.luxera.gomoku",
                catalogue.find("gomoku.game_make_move").orElseThrow().applicationId());
    }

    @Test
    void toolsListCanBeNarrowedByCapability() throws Exception {
        String body = mcp("agent-narrow", rpc(1, "tools/list", "{\"capabilityId\":\"game.play\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(toolNamed(body, "tictactoe.game_make_move").isObject());
        assertTrue(body.contains("gomoku.game_make_move"));
        assertTrue(!body.contains("reminder.reminder_create"),
                "按能力收窄之后不该还带着别的能力域的动作");
    }

    /** 短名撞车 → 整个目录改用全名, 而不是只给其中一个改。 */
    @Test
    void collidingShortNamesFallBackToTheFullApplicationId() {
        // 用一份独立的注册表: 共享的那份是启动时装好的, 往里塞测试应用会污染别的用例。
        // 发现链要的是"注册表 ∩ 账本"的交点, 所以这里也得配一个 catalogue —— 账本里没有
        // 这两个应用, 于是按 ApplicationCatalogue 的语义它们是在架的(见该类的类注释)。
        ManifestRegistry isolated = new ManifestRegistry();
        isolated.register(minimalManifest("com.luxera.alpha.game"));
        isolated.register(minimalManifest("com.luxera.beta.game"));

        List<String> names = new McpToolCatalog(new ApplicationCatalogue(isolated, applications),
                objectMapper).tools(null, null).stream()
                .map(McpToolCatalog.McpTool::name)
                .toList();

        assertEquals(List.of("com_luxera_alpha_game.game_make_move",
                "com_luxera_beta_game.game_make_move"), names);
    }

    // ─────────────────────────── 执行 ───────────────────────────

    /**
     * 这个类里最重要的一条: <b>MCP 客户端写下的那一手, 真人从 REST 读同一个 URI 时看得见。</b>
     *
     * <p>真人开一局、落一子(REST), MCP 客户端应手(MCP), 然后真人再读 —— 一行资源, 两个
     * principal, 两条传输。若适配器自己在内存里维护了什么"工具状态", 这条立刻红。
     */
    @Test
    void aMoveMadeOverMcpLandsOnTheSameResourceTheHumanReadsOverRest() throws Exception {
        String human = "human-" + UUID.randomUUID();
        String uri = openGameAsHuman(human);

        mvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", bearer(human))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", uri, "{\"position\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.state.board[0]").value("X"));

        // Agent 是新来的, 坐下就是 O —— 正好轮到它走
        String agent = "agent-" + UUID.randomUUID();
        joinForAgent(agent, uri);

        mcpWithKey(agent, callBody(1, "tictactoe.game_make_move",
                "{\"target\":\"" + uri + "\",\"position\":4}"), "mcp-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.status").value("SUCCESS"))
                .andExpect(jsonPath("$.result.structuredContent.result.board[4]").value("O"));

        // 真人这一侧: 同一个 URI, 同一个读路径(create=1, 两手棋=2/3)
        mvc.perform(get("/api/v1/resources").param("uri", uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state.board[0]").value("X"))
                .andExpect(jsonPath("$[0].state.board[4]").value("O"))
                .andExpect(jsonPath("$[0].version").value(3));
    }

    /** 重放: 同一个幂等键再来一次 → 网关重放, 不落新的一手, 而且响应头照 REST 那样标出来。 */
    @Test
    void replayingOverMcpIsFlaggedTheSameWayRestDoes() throws Exception {
        String agent = "agent-" + UUID.randomUUID();
        String uri = openGameAsAgent(agent);
        String key = "mcp-replay-" + UUID.randomUUID();
        String create = callBody(1, "tictactoe.game_create", "{\"target\":\"" + uri + "\"}");

        String first = mcpWithKey(agent, create, key)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String replay = mcpWithKey(agent, create, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 两份都用同一种方式解码之后再比 —— MockMvc 的 getContentAsString() 默认按 ISO-8859-1
        // 解, 直接拿它和 content().string(...) 比会把中文变成乱码, 于是"逐字节相同"这条
        // 断言会以"看起来只差编码"的样子失败。
        assertEquals(first, replay, "重放必须逐字节相同 —— 连事件时间戳都不许变");
    }

    /**
     * R6 的验收线: <b>走完整条 MCP 链路也不会有 ApplicationSession 冒出来。</b>
     *
     * <p>{@code sessions.count()} 的前后对比单独写在一条用例里, 而不是混在别的断言中间 ——
     * 这条性质被破坏时, 红的地方应该一眼就是它。旁边那半条断言(棋盘真的改了)是必需的:
     * 少了它, "没建会话"在"什么都没执行"的时候也成立。
     */
    @Test
    void aFullMcpRoundTripCreatesNoApplicationSessions() throws Exception {
        String agent = "agent-" + UUID.randomUUID();
        String uri = openGameAsAgent(agent);

        long before = sessions.count();
        int protocolBefore = protocolSessions.size();

        mcp("agent-init-roundtrip", rpc(1, "initialize", "{}")).andExpect(status().isOk());
        mcpWithKey(agent, callBody(2, "tictactoe.game_create", "{\"target\":\"" + uri + "\"}"),
                "k-create-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));
        mcpWithKey(agent, callBody(3, "tictactoe.game_make_move",
                        "{\"target\":\"" + uri + "\",\"position\":4}"),
                "k-move-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        assertEquals(before, sessions.count(), "MCP 的往返不得创建 ApplicationSession");
        assertTrue(protocolSessions.size() > protocolBefore,
                "协议会话倒是该有一个 —— 否则上面那条断言可能只是因为什么都没发生");

        mvc.perform(get("/api/v1/resources").param("uri", uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state.board[4]").value("X"));
    }

    /** 写动作没带幂等键: 由网关按动作的权限级别回, 适配器不另判一套"写动作要不要键"。 */
    @Test
    void aWriteWithoutAnIdempotencyKeyIsReportedAsAToolErrorNotAProtocolError() throws Exception {
        String agent = "agent-" + UUID.randomUUID();
        String uri = openGameAsAgent(agent);

        mcp(agent, callBody(1, "tictactoe.game_create", "{\"target\":\"" + uri + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.status")
                        .value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.result.structuredContent.error.code")
                        .value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /** 幂等键也可以写在 arguments 里 —— 给那些设不了请求头的客户端留的路。 */
    @Test
    void theIdempotencyKeyMayTravelInsideTheArguments() throws Exception {
        String agent = "agent-" + UUID.randomUUID();
        String uri = openGameAsAgent(agent);

        mcp(agent, callBody(1, "tictactoe.game_create",
                "{\"target\":\"" + uri + "\",\"_idempotencyKey\":\"arg-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    /**
     * READ 动作不要键 —— 而且读到的必须是<b>真人刚下的那盘棋</b>, 不是别的什么。
     *
     * <p>Agent 也得先<em>在这一局里</em>: 发现不是参与(见 {@code toolsListExposesTheActionCatalogue}),
     * 但读取是。这两个断言放在一起, 才说明"能看见"与"能动"之间隔着的是会话中的参与者身份,
     * 而不是目录里有没有。
     */
    @Test
    void readToolsNeedNoKeyButStillNeedToBeInTheSession() throws Exception {
        String human = "human-" + UUID.randomUUID();
        String uri = openGameAsHuman(human);
        humanMove(human, uri, 0);
        String agent = "agent-" + UUID.randomUUID();

        String read = callBody(1, "tictactoe.game_state", "{\"target\":\"" + uri + "\"}");

        mcp(agent, read)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.error.code").value("NOT_A_PARTICIPANT"));

        joinForAgent(agent, uri);

        mcp(agent, read)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.result.board[0]").value("X"))
                .andExpect(jsonPath("$.result.structuredContent.result.moves").value(1));
    }

    @Test
    void anUnknownToolIsInvalidParams() throws Exception {
        mcp("agent-unknown", callBody(1, "tictactoe.game_teleport", "{\"target\":\"game://session/x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(McpJsonRpc.INVALID_PARAMS))
                .andExpect(jsonPath("$.error.data.code").value("TOOL_NOT_FOUND"));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private ResultActions mcp(String agentId, String body) throws Exception {
        return mcp(agentId, body, null, null);
    }

    private ResultActions mcpWithSession(String agentId, String body, String sessionId) throws Exception {
        return mcp(agentId, body, sessionId, null);
    }

    private ResultActions mcpWithKey(String agentId, String body, String key) throws Exception {
        return mcp(agentId, body, null, key);
    }

    private ResultActions mcp(String agentId, String body, String sessionId, String idempotencyKey)
            throws Exception {
        MockHttpServletRequestBuilder request = post(MCP)
                .header("X-Mcp-Principal", "AGENT:" + agentId)
                .header("X-Mcp-Service-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    /** 真人那一侧: REST 开一个会话 + 开一局, 返回棋盘的资源 URI。 */
    private String openGameAsHuman(String userId) throws Exception {
        String launchBody = mvc.perform(post("/api/v1/applications/" + APP_ID + "/sessions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String uri = "game://session/" + objectMapper.readTree(launchBody).path("sessionId").asText();

        mvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", bearer(userId))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isOk());
        return uri;
    }

    /** 真人走一步 —— 让被测的那次 MCP 读/写有一个"他刚做的事"可看。 */
    private void humanMove(String userId, String uri, int position) throws Exception {
        mvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", bearer(userId))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", uri, "{\"position\":" + position + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.state.board[" + position + "]").value("X"));
    }

    private static String executeBody(String action, String target, String inputJson) {
        return """
                {"action":"%s","target":%s,"input":%s}"""
                .formatted(action, target == null ? "null" : "\"" + target + "\"", inputJson);
    }

    /**
     * AGENT 开不了 REST 那一面 —— 那个面只认 JWT, 而 MCP 客户端没有 JWT。所以这里直接调
     * {@code ApplicationSessionService}。这与 {@code check-lap.sh} 用 SQL 造出同一个状态是同一
     * 件事: HTTP 面上够不到的状态, 只能从服务层造。
     *
     * <p>v2 里 Agent <em>可以</em>自己开局 —— 它就是普通参与者(原则 4), 没有"Agent 专用"的路。
     * 于是这个夹具与真人那条路走的是同一个方法, 只是调用方不同。
     */
    private String openGameAsAgent(String agentId) {
        ResolvedPrincipal principal = agentPrincipal(agentId);
        return "game://session/" + sessionService.launch(APP_ID, principal).getId();
    }

    /**
     * 把一个 Agent 加进这一局。<b>这就是 v2 取代"装一次"的那件事</b>: v1 里它是一次永久的
     * 安装(viaInvitation 无从谈起), v2 里它是一次会话内的加入 —— 会话没了, 关系也就没了。
     *
     * <p>{@code viaInvitation=true} 因为会话默认 {@code INVITE_ONLY}: 被邀请进来正是 Agent 的
     * 处境, 而能替别人加入的路只有邀请这一条。
     */
    private void joinForAgent(String agentId, String uri) {
        participantService.join(sessionIdOf(uri), agentPrincipal(agentId),
                SessionParticipantRecord.ROLE_MEMBER, true);
    }

    private static String sessionIdOf(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    private static ResolvedPrincipal agentPrincipal(String agentId) {
        return new ResolvedPrincipal(PrincipalType.AGENT, agentId, agentId, null, null,
                UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_MCP);
    }

    private static String bearer(String userId) {
        return StubPrincipalTokenReader.bearer(PrincipalType.HUMAN, userId);
    }

    private static String rpc(int id, String method, String paramsJson) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}"""
                .formatted(id, method, paramsJson == null ? "{}" : paramsJson);
    }

    private static String callBody(int id, String tool, String argumentsJson) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call",
                 "params":{"name":"%s","arguments":%s}}"""
                .formatted(id, tool, argumentsJson);
    }

    private JsonNode toolNamed(String body, String name) throws Exception {
        for (JsonNode tool : objectMapper.readTree(body).path("result").path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return objectMapper.createObjectNode();
    }

    /** 一份最小可用的 manifest —— 只用来说明"两个应用里各有一个同名动作"。 */
    private static ApplicationManifest minimalManifest(String applicationId) {
        return new ApplicationManifest(
                new ApplicationManifest.Identity(applicationId, applicationId, "1.0.0", null, null),
                List.of(),
                List.of(new ApplicationManifest.ActionDecl("game.make_move", "game.play",
                        "落子", null, PermissionLevel.EXECUTE, RiskLevel.LOW,
                        AttentionPolicy.FOCUSED, null, null)),
                List.of(),
                List.of(),
                List.of(),
                new ApplicationManifest.RuntimeDecl(RuntimeType.NATIVE, null), null);
    }
}
