package com.luxera.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2.2 §6.3 —— 客户端面十个端点在<b>真实过滤器链</b>上的可达性与语义。
 *
 * <h2>这组用例要守的到底是什么</h2>
 *
 * <p>不是"每个端点各回一个 200"。是 §6.4 那个承诺本身: <b>前端(真人 JWT)与 Agent
 * (接入钥匙换来的令牌)打的是同一套端点, 差别只在"你是哪个账号"。</b>所以这里的核心用例是
 * {@link #theFrontendAndTheAgentLandOnTheSameConversationFromOppositeEnds} —— 两个不同的
 * 身份调同一个 {@code GET /api/client/conversations}, 得到同一个 {@code conversationId},
 * 而各自的 {@code accountId} 恰好是对方。一条断言同时说了三件事: 端点共用、身份被正确解析、
 * 访问范围由身份结构性决定。
 *
 * <h2>为什么这一层必须在真启动类上跑</h2>
 *
 * <p>与 {@code ExternalChatAccessTest} 逐字相同的理由: 两处要害
 * (SecurityConfig 的放行、{@code application.principal} 里钥匙解出的 agent 从哪来)都落在
 * 测试启动类刻意不扫的包里。少了这一层, 一个语法完全正确的客户端面会在生产上表现为
 * "每个请求都是 Spring 的默认 403", 而单测全绿。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@Import(ClientPlatformStub.class)
@AutoConfigureMockMvc
class ClientApiHttpTest {

    /** 与 application-test.yml 里的 app.chat.access.admin-key 一致。 */
    private static final String ADMIN_KEY = "test-chat-access-admin-key";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    UserRepository users;
    @Autowired
    ConversationService conversations;
    @Autowired
    SimulatorPairingService pairing;

    // ── 共用一套端点: 两个身份, 一个会话 ─────────────────────────────────────

    /**
     * 前端与 Agent 打同一个端点, 各自只看到自己那一侧。
     *
     * <p>这是整个 §6.3 最要紧的一条断言。把"端点共用"写成一个 {@code GET} 是不够的 ——
     * 真正要成立的是"共用之后权限没有互相串味": 真人看到的是 Agent 的账号, Agent 看到的是
     * 真人的账号, 而两者指的是同一段会话。任何一侧拿到了对方不该看的东西(比如 Agent 看到了
     * 真人数据库里的其他会话), 这条用例都会红。
     */
    @Test
    void theFrontendAndTheAgentLandOnTheSameConversationFromOppositeEnds() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "小满");
        String human = newHuman();
        Conversation conv = conversations.create(human, agentId, "同一个会话", "小满");

        // ① 前端: 拿自己的 JWT 登录, 问"我是哪个账号"
        String humanToken = humanToken(human);
        JsonNode session = json(mockMvc.perform(post("/api/client/login")
                        .header("Authorization", "Bearer " + humanToken))
                .andReturn(), 200);
        assertEquals(human, session.path("accountId").asText(),
                "真人的聊天账号就是它自己的 userId —— 登录这一步不换身份");
        assertEquals(humanToken, session.path("token").asText(),
                "真人那一支原样回显令牌, 不重签: 重签会让同一个用户同时有两个有效令牌");
        assertTrue(session.path("expiresAt").isTextual(),
                "expiresAt 必须是令牌自己的 exp(不是 now + 有效期)");

        // ② Agent: 拿接入钥匙换令牌
        String agentToken = agentToken(agentId);

        // ③ 同一个端点, 两个身份 → 同一个 conversationId, 各自的 accountId 是对方
        JsonNode mine = json(mockMvc.perform(get("/api/client/conversations")
                .header("Authorization", "Bearer " + humanToken)).andReturn(), 200);
        JsonNode theirs = json(mockMvc.perform(get("/api/client/conversations")
                .header("Authorization", "Bearer " + agentToken)).andReturn(), 200);

        assertEquals(1, mine.size(), "真人应看到这一段会话, 实际: " + mine);
        assertEquals(1, theirs.size(), "Agent 应看到同一段会话, 实际: " + theirs);
        assertEquals(conv.getId(), mine.get(0).path("conversationId").asText());
        assertEquals(conv.getId(), theirs.get(0).path("conversationId").asText());
        assertEquals(agentAccount, mine.get(0).path("accountId").asText(),
                "真人看到的对方账号是 Agent 的聊天账号");
        assertEquals(human, theirs.get(0).path("accountId").asText(),
                "Agent 看到的对方账号是那个真人的账号");

        // ④ 列表页不带正文预览 —— §8.2.4: "她还没读就知道内容"必须是做不到的状态
        assertFalse(mine.get(0).has("lastMessage"),
                "列表里出现了 lastMessage —— 正文只能从 GET .../messages 出来, 实际: " + mine);
        assertFalse(mine.get(0).has("preview"));
        assertFalse(mine.get(0).has("content"));
    }

    // ── 登录: 两条路与它们的门 ────────────────────────────────────────────────

    /**
     * 两条路各自的门都是关着的。
     *
     * <p>三条断言分别是三种"不该通过": 不带任何凭据、拿一把不存在的钥匙、拿管理密钥走 login。
     * 最后一条是 §6.4 的核心 —— 管理密钥代表平台自己, 而平台自己不是一个聊天账号; 放它进来
     * 之后下游每一处"这个账号参与的会话"都会退化成"全部会话"。
     */
    @Test
    void loginRefusesEveryoneWhoBringsTheWrongKindOfCredential() throws Exception {
        MvcResult noCredential = mockMvc.perform(post("/api/client/login")).andReturn();
        assertEquals(401, noCredential.getResponse().getStatus());
        assertEquals("CLIENT_UNAUTHENTICATED", json(noCredential, 401).path("code").asText());

        MvcResult badKey = mockMvc.perform(post("/api/client/login")
                .header("X-Api-Key", "cak_THISKEYDOESNOTEXIST")).andReturn();
        assertEquals(401, badKey.getResponse().getStatus());
        assertEquals("ACCESS_KEY_UNKNOWN", json(badKey, 401).path("code").asText(),
                "接入面的失败码原样保留 —— 同一个失败码在两个面上不能有两种含义");

        MvcResult adminOnClientLogin = mockMvc.perform(post("/api/client/login")
                .header("X-Admin-Key", ADMIN_KEY)).andReturn();
        assertEquals(403, adminOnClientLogin.getResponse().getStatus(),
                "管理密钥走 login 会拿到一个'不属于任何人的会话' —— 它看起来与'你还没有会话'一样");
        assertTrue(json(adminOnClientLogin, 403).path("hint").asText().contains("provision"),
                "拒绝的时候要告诉它该走哪条路");
    }

    /**
     * 一个还没有聊天账号的 Agent 拿钥匙登录 → 409, 而不是 401。
     *
     * <p>区分这两者不是为了好看: 401 会让调用方去翻自己的钥匙, 而钥匙是对的 —— 它翻到天亮也
     * 翻不出结果。409 说的是"你的凭据没问题, 是状态不对"(与对外开放面把"管理密钥没配"
     * 答成 503 是同一条理由)。
     */
    @Test
    void anAgentWithoutAChatAccountIsToldToProvisionFirst() throws Exception {
        String key = issueKey("还没有账号的agent", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(post("/api/client/login").header("X-Api-Key", key))
                .andReturn();
        assertEquals(409, result.getResponse().getStatus(),
                "钥匙是对的, 只是它背后还没有聊天账号 —— 这不是认证失败");
        assertEquals("CLIENT_CONFLICT", json(result, 409).path("code").asText());
    }

    // ── 收发、分页、已读、免打扰 ──────────────────────────────────────────────

    /**
     * 消息读得回来、发得出去、幂等键有效、分页游标能上翻。
     *
     * <p>这一条是"这个面能用"的底线。分页那一段特别重要: 游标是
     * {@code createdAt|messageId} 两段, 而它错起来的症状是**悄悄丢消息** —— 同毫秒的两条
     * 只回来一条, 任何人都不会发现。
     */
    @Test
    void messagesCanBeSentReadAndPagedWithoutLosingAny() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "分页测试");
        String human = newHuman();
        conversations.create(human, agentId, "分页会话", "分页测试");
        String humanToken = humanToken(human);
        String agentToken = agentToken(agentId);

        // 1. 真人发三条 —— 通过客户端面, 走真实的写入链
        String firstId = null;
        for (int i = 1; i <= 3; i++) {
            JsonNode sent = json(mockMvc.perform(post(path(agentAccount) + "/messages")
                            .header("Authorization", "Bearer " + humanToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("content", "分页第 " + i + " 条", "idempotencyKey", "p-" + i))))
                    .andReturn(), 201);
            assertNotNull(sent.path("messageId").asText());
            assertTrue(sent.path("sentAt").isTextual(),
                    "sentAt 不能是空的 —— 空值说明构造视图时事务还没有 flush");
            if (i == 1) firstId = sent.path("messageId").asText();
        }

        // 2. 幂等: 同一个键重发不产生第四条
        JsonNode replayed = json(mockMvc.perform(post(path(agentAccount) + "/messages")
                        .header("Authorization", "Bearer " + humanToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("content", "分页第 1 条", "idempotencyKey", "p-1"))))
                .andReturn(), 201);
        assertEquals(firstId, replayed.path("messageId").asText(),
                "重放应当拿回同一条消息 —— 超时重发不该变成两句一样的话");

        // 3. 读回来: 到序(最新在前), 三条都在
        JsonNode page = json(mockMvc.perform(get(path(agentAccount) + "/messages")
                .header("Authorization", "Bearer " + humanToken)).andReturn(), 200);
        assertEquals(3, page.path("messages").size(), "实际: " + page);
        assertEquals("分页第 3 条", page.path("messages").get(0).path("content").asText(),
                "应该是到序: 点开一个人先看到最近说的话");
        assertEquals(human, page.path("messages").get(0).path("senderAccountId").asText(),
                "senderAccountId 是与 accountId 同一个命名空间里的值");
        assertFalse(page.path("hasMore").asBoolean());

        // 4. 每页 1 条往前翻, 一条都不能丢、也不能重复
        Map<String, Integer> seen = new HashMap<>();
        String cursor = null;
        for (int round = 0; round < 5; round++) {
            var request = get(path(agentAccount) + "/messages").param("limit", "1")
                    .header("Authorization", "Bearer " + humanToken);
            if (cursor != null) request = request.param("cursor", cursor);
            JsonNode one = json(mockMvc.perform(request).andReturn(), 200);
            for (JsonNode m : one.path("messages")) {
                seen.merge(m.path("messageId").asText(), 1, Integer::sum);
            }
            if (!one.path("hasMore").asBoolean()) break;
            cursor = one.path("nextCursor").asText();
            assertFalse(cursor.isBlank(), "hasMore 为真时必须给出 nextCursor");
        }
        assertEquals(3, seen.size(), "上翻三页应当正好看到三条, 实际: " + seen);
        seen.values().forEach(c -> assertEquals(1, c, "同一条消息被翻了两次: " + seen));

        // 5. 坏游标是 400, 而不是"尽力解释成一个位置" —— 后者会表现为翻页悄悄丢消息
        MvcResult badCursor = mockMvc.perform(get(path(agentAccount) + "/messages")
                .param("cursor", "这不是一个游标")
                .header("Authorization", "Bearer " + humanToken)).andReturn();
        assertEquals(400, badCursor.getResponse().getStatus());
        assertEquals("CLIENT_BAD_REQUEST", json(badCursor, 400).path("code").asText());

        // 6. 已读: 收件人的角标清零, 而发送者自己从来就没有过角标
        assertEquals(0, unreadOf(humanToken, agentAccount),
                "自己发的消息不该给自己涨未读");
        assertEquals(3, unreadOf(agentToken, human),
                "三条都发给了 Agent —— 未读是逐人的, 它该有三个");
        json(mockMvc.perform(post(path(human) + "/read")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn(), 200);
        assertEquals(0, unreadOf(agentToken, human));
    }

    // ── 联系人资料 ────────────────────────────────────────────────────────────

    /**
     * "这个人是谁"—— 平台只回答它知道的那么多。
     *
     * <p>真人与 Agent 拿到的东西**刻意不同**: 前端要在一屏里显示对方的名字; 而 Agent 看到的是
     * 它自己该建的那个 {@code PersonObject}, 平台这边的昵称是另一个人的"备注", 不该顺手交给它
     * (§6.3 第 9 项)。同一条断言也顺带钉住"这个端点不是账号枚举器": 没会话的账号一律 404。
     */
    @Test
    void theContactEndpointAnswersDifferentThingsForTheTwoSides() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "小满");
        String human = newHuman();
        conversations.create(human, agentId, "通讯录会话", "小满");
        String humanToken = humanToken(human);
        String agentToken = agentToken(agentId);

        JsonNode forHuman = json(mockMvc.perform(get("/api/client/contacts/" + agentAccount)
                .header("Authorization", "Bearer " + humanToken)).andReturn(), 200);
        assertEquals(agentAccount, forHuman.path("accountId").asText());
        assertEquals("小满", forHuman.path("displayName").asText(),
                "前端要在一屏里显示对方的名字");
        assertNullOrAbsent(forHuman, "avatarUrl",
                "users 表里没有头像列 —— 不编一个出来");

        JsonNode forAgent = json(mockMvc.perform(get("/api/client/contacts/" + human)
                .header("Authorization", "Bearer " + agentToken)).andReturn(), 200);
        assertNullOrAbsent(forAgent, "displayName",
                "Agent 这一侧的名字由它自己填 —— 平台不把'备注'交给它, 实际: " + forAgent);

        // 没有会话的账号 → 404: 否则这个端点就是一台账号枚举器
        MvcResult stranger = mockMvc.perform(get("/api/client/contacts/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + humanToken)).andReturn();
        assertEquals(404, stranger.getResponse().getStatus(),
                "拿一个 id 试一次就能知道它存不存在 —— 那是一个账号枚举接口");
        assertEquals("CLIENT_NOT_FOUND", json(stranger, 404).path("code").asText());
    }

    // ── 租户边界 ──────────────────────────────────────────────────────────────

    /**
     * 拿 A 的令牌够不到 B 的会话, 而且答案是 <b>404 而不是 403</b>。
     *
     * <p>403 等于承认"这段会话是真实存在的, 只是不归你" —— 对一段猜出来的会话 id 来说, 那是
     * 一条他不该拿到的信息。这里更硬一层: 寻址用的是**对方的账号**, 而候选集是"我参与的会话"
     * —— 越界根本没有出现在候选集里的机会, 它不是一条检查, 是一条结构性事实。
     */
    @Test
    void oneTenantCannotReachAnothersConversation() throws Exception {
        String agentA = UUID.randomUUID().toString();
        String agentB = UUID.randomUUID().toString();
        String accountA = agentWithAccount(agentA, "A");
        String accountB = agentWithAccount(agentB, "B");
        String humanA = newHuman();
        String humanB = newHuman();
        conversations.create(humanA, agentA, "A的会话", "A");
        conversations.create(humanB, agentB, "B的会话", "B");

        // 从 A(agent) 的视角: 它那一段会话的对方是 humanA, 而 humanB / accountB 都是陌生人
        String tokenA = agentToken(agentA);
        assertEquals(404, status(get(path(humanB) + "/messages"), tokenA));
        assertEquals(404, status(get(path(accountB) + "/messages"), tokenA));
        assertEquals(404, status(get("/api/client/contacts/" + accountB), tokenA));
        assertEquals(404, status(post(path(humanB) + "/messages"), tokenA,
                "{\"content\":\"我不该出现在这里\"}"));
        assertEquals(404, status(put(path(humanB) + "/notification"), tokenA,
                "{\"muted\":true,\"pinned\":false}"));
        assertEquals(404, status(post(path(humanB) + "/read"), tokenA, "{}"));

        // 不存在的账号与别人的账号答同一个东西 —— 调用方分不出来
        assertEquals(404, status(get(path(UUID.randomUUID().toString()) + "/messages"), tokenA));

        // 而它自己那一条照常能用 —— 上面六条不是"所有人都用不了"的假绿
        assertEquals(200, status(get(path(humanA) + "/messages"), tokenA));
        JsonNode mine = json(mockMvc.perform(get("/api/client/conversations")
                .header("Authorization", "Bearer " + tokenA)).andReturn(), 200);
        assertEquals(1, mine.size());
        assertEquals(humanA, mine.get(0).path("accountId").asText(),
                "A 看到的对方账号是那个真人, 不是另一个 agent 的账号");
    }

    // ── §6.5 铸号 ─────────────────────────────────────────────────────────────

    /**
     * 为 Agent 铸一个聊天账号 —— 只有管理密钥能做, 而它是<b>幂等</b>的。
     *
     * <p>幂等那一条是这段代码最容易被写漏的地方: 账号ID 一旦发出去就不可回收, 而"响应丢了"
     * 的重试是这条跨平台调用序列上最常见的一种失败。所以这里连发两次同一个 {@code requestId},
     * 断言同一个账号 —— 一个幂等键只在一个进程内有效、或者靠"先查再建"(两次独立事务)做出来
     * 的幂等, 都会在这里红。
     */
    @Test
    void provisioningIsAdminOnlyAndIdempotent() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String owner = newHuman();
        String requestId = "req-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", requestId, "displayName", "铸号测试",
                "ownerAccountId", owner, "relationshipType", "FRIEND", "agentId", agentId));

        JsonNode created = json(mockMvc.perform(post("/api/client/provision")
                .header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 201);
        assertNotNull(created.path("accountId").asText());
        assertEquals(requestId, created.path("requestId").asText());
        assertEquals(owner, created.path("ownerAccountId").asText(),
                "两端 id 一起交回去 —— 免得对面为了一个已知的值多走一次网络");
        assertNotNull(created.path("pairingCode").asText(),
                "还没有配对的账号必须给出配对码, 否则那个 Agent 永远拿不到设备凭据");

        JsonNode again = json(mockMvc.perform(post("/api/client/provision")
                .header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 201);
        assertEquals(created.path("accountId").asText(), again.path("accountId").asText(),
                "同一个 requestId 必须命中同一个账号 —— 账号 id 发出去就收不回来了");

        // 绑上了 agent, 于是它立刻能登录, 而且通讯录里已经有这一段会话
        String accountId = created.path("accountId").asText();
        JsonNode session = json(mockMvc.perform(post("/api/client/login")
                .header("X-Api-Key", issueKey("铸号测试", agentId))).andReturn(), 200);
        assertEquals(accountId, session.path("accountId").asText(),
                "铸出来的账号与钥匙解出的 agent 必须是同一个 —— 绑定掉在那里没生效");
        JsonNode ownerList = json(mockMvc.perform(get("/api/client/conversations")
                        .header("Authorization", "Bearer " + humanToken(owner)))
                .andReturn(), 200);
        assertEquals(1, ownerList.size(), "绑了 agent 却没有会话, 它就不会出现在任何人的列表里");
        assertEquals(accountId, ownerList.get(0).path("accountId").asText());

        // 两把非管理凭据都进不来, 而且理由分得开
        String key = issueKey("想铸号的第三方", agentId);
        MvcResult clientKey = mockMvc.perform(post("/api/client/provision")
                .header("X-Api-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(403, clientKey.getResponse().getStatus(),
                "接入钥匙能凭空铸账号的话, 每个租户都能给自己造无穷多个身份");
        assertEquals("CLIENT_FORBIDDEN", json(clientKey, 403).path("code").asText());

        MvcResult human = mockMvc.perform(post("/api/client/provision")
                .header("Authorization", "Bearer " + humanToken(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(401, human.getResponse().getStatus(),
                "这个端点只认管理密钥 —— 真人的令牌在这里什么都不是");

        // 缺幂等键 → 400: 它是这个端点唯一不能省的东西
        MvcResult noAnchor = mockMvc.perform(post("/api/client/provision")
                .header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"没有锚点\"}")).andReturn();
        assertEquals(400, noAnchor.getResponse().getStatus());
        assertEquals("CLIENT_BAD_REQUEST", json(noAnchor, 400).path("code").asText());
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static String path(String peerAccountId) {
        return "/api/client/conversations/" + peerAccountId;
    }

    /**
     * 「这个字段是空的」—— 兼容两种空法: 值本身是 null, 或者整个键被序列化器略过
     * ({@code default-property-inclusion: non_null} 会把 null 字段整个丢掉)。
     *
     * <p>断言写成"要么没有、要么是 null"而不是只认其中一种, 是因为哪一个会发生在很大程度上
     * 取决于 Jackson 的配置 —— 而这条用例要说的根本不是序列化器怎么处理空值, 而是"平台不知道
     * 这个值, 于是它不编一个出来"。
     */
    private static void assertNullOrAbsent(JsonNode node, String field, String message) {
        JsonNode value = node.path(field);
        assertTrue(value.isMissingNode() || value.isNull(), message + " —— 实际: " + node);
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                       String token) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                       String token, String jsonBody) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(jsonBody))
                .andReturn().getResponse().getStatus();
    }

    private int unreadOf(String token, String peerAccountId) throws Exception {
        JsonNode listed = json(mockMvc.perform(get("/api/client/conversations")
                .header("Authorization", "Bearer " + token)).andReturn(), 200);
        for (JsonNode c : listed) {
            if (peerAccountId.equals(c.path("accountId").asText())) {
                return c.path("unreadCount").asInt();
            }
        }
        throw new AssertionError("列表里没有与 " + peerAccountId + " 的会话: " + listed);
    }

    private String humanToken(String accountId) {
        return jwtUtil.generateToken(accountId, "client-http-test", PrincipalType.HUMAN);
    }

    private String agentToken(String agentId) throws Exception {
        return json(mockMvc.perform(post("/api/client/login")
                .header("X-Api-Key", issueKey("客户端面测试", agentId))).andReturn(), 200)
                .path("token").asText();
    }

    private String agentWithAccount(String agentId, String displayName) {
        SimulatorPairingService.ProvisionResult r = pairing.provisionSimulatorAccount(displayName);
        pairing.attachCompanion(r.deviceId(), agentId);
        return r.accountId();
    }

    private String issueKey(String name, String agentId) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/v1/chat/clients")
                .header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name, "agentId", agentId))))
                .andReturn(), 201);
        return body.path("apiKey").asText();
    }

    private String newHuman() {
        User u = new User();
        u.setUsername("client-http-" + UUID.randomUUID().toString().substring(0, 12));
        u.setPasswordHash("{noop}unused");
        u.setEmail(UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        u.setUserKind("HUMAN");
        return users.save(u).getId();
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应码不对, 响应体: " + body(result));
        return objectMapper.readTree(body(result));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
