package com.luxera.companion.access.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 对外开放面在<b>过滤器链</b>上的可达性与租户边界。
 *
 * <p>为什么这条断言只能落在真启动类上: 测试启动类 {@code ChatPlatformTestApplication} 刻意
 * 不扫 {@code com.luxera.companion.access} 与 {@code application.principal} —— 那组测试要守的
 * 是"聊天平台不认识任何具体应用"。而接入面的两处要害(过滤器放行、钥匙解出的 agent 从哪来)
 * 都在这两个包里。少了这一层, 一个语法完全正确的接入面会在生产上表现为"每个请求都是
 * Spring 的默认 403", 而单测全绿 —— MCP 那次就是这么失败的(见 {@code McpEndpointSecurityTest})。
 *
 * <p>三条独立的性质:
 * <ol>
 *   <li><b>放行</b> —— 带 {@code X-Api-Key} 的请求必须真的到达控制器。把
 *       {@code /api/v1/chat/**} 收回 {@code anyRequest()} 后面, 这一条立刻变红。</li>
 *   <li><b>租户边界</b> —— 拿 A 的钥匙够不到 B 的会话, 且答案是 404 而不是 403。</li>
 *   <li><b>两把钥匙不通用</b> —— 管理钥匙进不了客户端面, 客户端钥匙也进不了管理面。</li>
 * </ol>
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@Import(ExternalChatAccessTest.StubAgentPlatform.class)
@AutoConfigureMockMvc
class ExternalChatAccessTest {

    /** 与 application-test.yml 里的 app.chat.access.admin-key 一致。 */
    private static final String ADMIN_KEY = "test-chat-access-admin-key";

    /**
     * 仓 2 的实现不在这个 classpath 上(它在另一个仓库)。这里要验的是接入面与过滤器链,
     * 目录查询给一个静默桩即可 —— 与 {@code McpEndpointSecurityTest} 同一个理由。
     */
    @TestConfiguration
    static class StubAgentPlatform {
        @Bean
        @Primary
        CompanionDirectoryPort stubCompanionDirectory() {
            return new CompanionDirectoryPort() {
                @Override
                public CompanionRef requireOwned(String userId, String companionId) {
                    return new CompanionRef(companionId, "桩数字人", companionId);
                }

                @Override
                public void onUserMessage(String userId, String companionId, String conversationId,
                                          List<MessageView> messages) {
                    // 进程外: 聊天平台 fire-and-forget
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ConversationService conversations;
    @Autowired
    MessageRepository messages;
    @Autowired
    UserRepository users;
    @Autowired
    SimulatorPairingService pairing;

    // ── 租户边界 ──────────────────────────────────────────────────────────────

    @Test
    void aKeySeesAndTouchesOnlyTheAgentItWasIssuedFor() throws Exception {
        String agentA = UUID.randomUUID().toString();
        String agentB = UUID.randomUUID().toString();
        String key = issueKey("测试第三方程序", agentA);

        String human = newHuman();
        Conversation mine = conversations.create(human, agentA, null, "我的Agent");
        Conversation others = conversations.create(human, agentB, null, "别人的Agent");

        // 1. 列表: 只有自己的那一段
        JsonNode listed = json(mockMvc.perform(get("/api/v1/chat/conversations")
                .header("X-Api-Key", key)).andReturn(), 200);
        assertEquals(agentA, listed.path("agentId").asText(),
                "回显的 agentId 必须是平台确认的身份, 而不是请求里的任何东西");
        assertEquals(1, listed.path("conversations").size(), "看得到别人的会话就是租户边界破了");
        assertEquals(mine.getId(), listed.path("conversations").get(0).path("id").asText());

        // 2. 读别人的消息: 404 —— 不是 403。403 等于承认"这段会话存在, 只是不归你"。
        mockMvc.perform(get("/api/v1/chat/conversations/" + others.getId() + "/messages")
                        .header("X-Api-Key", key))
                .andExpect(result -> assertEquals(404, result.getResponse().getStatus(),
                        "越界必须是 404; 403 会告诉调用方那个会话 id 是真实存在的"));

        // 3. 往别人的会话里发消息: 同样 404, 且一个字都不能落库
        long before = messages.countByConversationId(others.getId());
        mockMvc.perform(post("/api/v1/chat/conversations/" + others.getId() + "/messages")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我不该出现在这里\"}"))
                .andExpect(result -> assertEquals(404, result.getResponse().getStatus()));
        assertEquals(before, messages.countByConversationId(others.getId()),
                "越界的写入被拒了但消息已经落库 —— 顺序写反了: 必须先判归属再写");

        // 4. 不存在的会话与别人的会话答同一个东西 —— 调用方分不出来
        mockMvc.perform(get("/api/v1/chat/conversations/" + UUID.randomUUID() + "/messages")
                        .header("X-Api-Key", key))
                .andExpect(result -> assertEquals(404, result.getResponse().getStatus()));
    }

    @Test
    void keylessAndWrongkeyRequestsAreRefusedByTheControllerNotBySpring() throws Exception {
        MvcResult noKey = mockMvc.perform(get("/api/v1/chat/conversations")).andReturn();
        assertEquals(401, noKey.getResponse().getStatus());
        JsonNode body = json(noKey, 401);
        assertTrue(body.has("error"), "回的不是接入面的错误体: " + body);
        assertFalse(body.has("path"),
                "这是 Spring 默认错误体 —— 说明请求根本没到 ChatAccessController: " + body);
        assertEquals("UNIDENTIFIED_PRINCIPAL", body.path("code").asText(), "响应体: " + body);

        MvcResult badKey = mockMvc.perform(get("/api/v1/chat/conversations")
                .header("X-Api-Key", "cak_THISKEYDOESNOTEXIST")).andReturn();
        assertEquals(401, badKey.getResponse().getStatus());
        assertEquals("ACCESS_KEY_UNKNOWN", json(badKey, 401).path("code").asText());
    }

    /** 两把钥匙各有各的门: 管理钥匙进不了聊天面, 接入钥匙进不了管理面。 */
    @Test
    void theTwoKeysAreNotInterchangeable() throws Exception {
        String key = issueKey("钥匙互不通用", UUID.randomUUID().toString());

        MvcResult adminOnChatFace = mockMvc.perform(get("/api/v1/chat/conversations")
                .header("X-Admin-Key", ADMIN_KEY)).andReturn();
        assertEquals(403, adminOnChatFace.getResponse().getStatus(),
                "管理钥匙走聊天面会给一个空列表 —— 那看起来和'你还没有会话'一模一样");

        MvcResult clientOnAdminFace = mockMvc.perform(get("/api/v1/chat/clients")
                .header("X-Api-Key", key)).andReturn();
        assertEquals(403, clientOnAdminFace.getResponse().getStatus(),
                "cak_ 钥匙能列出发钥匙的记录, 就等于每个租户都能看到别的租户的 agentId");

        MvcResult noKeyOnAdminFace = mockMvc.perform(get("/api/v1/chat/clients")).andReturn();
        assertEquals(401, noKeyOnAdminFace.getResponse().getStatus());
    }

    /** 管理面签出来的明文钥匙必须真的能用 —— 签发与校验走的是同一个哈希函数。 */
    @Test
    void theIssuedKeyIsListedWithoutItsHashAndWorksImmediately() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        MvcResult created = mockMvc.perform(post("/api/v1/chat/clients")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "刚签的", "agentId", agentId))))
                .andReturn();
        JsonNode body = json(created, 201);
        String plaintext = body.path("apiKey").asText();
        assertTrue(plaintext.startsWith("cak_"), "明文钥匙应当带 cak_ 前缀: " + plaintext);
        assertEquals(agentId, body.path("agentId").asText());
        assertFalse(body.has("apiKeyHash"), "响应里不能出现哈希");

        // 立刻可用: 列表里只有它这一个 agent 的会话(此刻还没有会话, 所以是空列表 + 正确的 agentId)
        JsonNode listed = json(mockMvc.perform(get("/api/v1/chat/conversations")
                .header("X-Api-Key", plaintext)).andReturn(), 200);
        assertEquals(agentId, listed.path("agentId").asText());

        // 列表接口不回哈希, 也不回明文
        JsonNode clients = json(mockMvc.perform(get("/api/v1/chat/clients")
                .header("X-Admin-Key", ADMIN_KEY)).andReturn(), 200);
        for (JsonNode c : clients.path("clients")) {
            assertFalse(c.has("apiKey"), "明文只出现一次, 之后补发不了");
            assertFalse(c.has("apiKeyHash"));
        }

        // 吊销之后同一把钥匙立刻失效
        mockMvc.perform(post("/api/v1/chat/clients/" + body.path("clientId").asText() + "/revoke")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()));
        mockMvc.perform(get("/api/v1/chat/conversations").header("X-Api-Key", plaintext))
                .andExpect(result -> assertEquals(401, result.getResponse().getStatus(),
                        "吊销之后还能用的话, 吊销就只是一个装饰"));
        assertNotEquals(clientId, body.path("clientId").asText(), "客户端 id 必须是新生成的");
    }

    // ── 收发与已读 ────────────────────────────────────────────────────────────

    @Test
    void sendingAndReadingBehaveLikeThePlatformsOwnAgentFace() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String key = issueKey("收发测试", agentId);
        String human = newHuman();
        Conversation conv = conversations.create(human, agentId, null, "小满");

        // 1. 发一句话
        JsonNode sent = json(mockMvc.perform(post("/api/v1/chat/conversations/" + conv.getId() + "/messages")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我在\",\"idempotencyKey\":\"k-" + conv.getId() + "\"}"))
                .andReturn(), 201);
        assertEquals("companion", sent.path("message").path("senderType").asText(),
                "从聊天平台看, 第三方 agent 就是会话的另一方");
        assertEquals("我在", sent.path("message").path("content").asText());
        String messageId = sent.path("message").path("id").asText();

        // 2. 同一个幂等键重放: 不产生第二条
        JsonNode replayed = json(mockMvc.perform(post("/api/v1/chat/conversations/" + conv.getId() + "/messages")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我在\",\"idempotencyKey\":\"k-" + conv.getId() + "\"}"))
                .andReturn(), 201);
        assertEquals(messageId, replayed.path("message").path("id").asText(),
                "重放应当拿回同一条消息 —— 超时重发不该变成两句一样的话");
        assertEquals(1, messages.countByConversationId(conv.getId()));

        // 3. messageKind 白名单: SYSTEM 是平台通告, 第三方不能伪造
        mockMvc.perform(post("/api/v1/chat/conversations/" + conv.getId() + "/messages")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"假公告\",\"messageKind\":\"SYSTEM\"}"))
                .andExpect(result -> assertEquals(400, result.getResponse().getStatus()));

        // 4. 真人说一句, 然后 agent 标已读
        conversations.addMessage(conv.getId(), "user", "在吗", null);
        JsonNode read = json(mockMvc.perform(post("/api/v1/chat/conversations/" + conv.getId() + "/read")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn(), 200);
        assertEquals(1, read.path("markedRead").asInt(), "这一段里只有真人那一条需要被标成已读");

        JsonNode again = json(mockMvc.perform(post("/api/v1/chat/conversations/" + conv.getId() + "/read")
                        .header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn(), 200);
        assertEquals(0, again.path("markedRead").asInt(), "第二次是'本来就已读', 与'标了'必须可区分");

        // 5. 消息列表读得回来
        JsonNode listed = json(mockMvc.perform(get("/api/v1/chat/conversations/" + conv.getId() + "/messages")
                .header("X-Api-Key", key)).andReturn(), 200);
        assertEquals(2, listed.path("messages").size());
        for (JsonNode m : listed.path("messages")) {
            assertFalse(m.has("senderId"),
                    "接入面不回 senderId —— 那是人类那一侧的账号 id, 对收发消息毫无用处");
        }
    }

    // ── 公开配对端点 ──────────────────────────────────────────────────────────

    /**
     * 配对码换凭据 —— 这条链路在此之前<b>没有任何生产调用者</b>: 平台会发码, 而码换不到东西。
     *
     * <p>用 {@code X-Forwarded-For} 给每个用例一个独立的 IP 桶: 限流器是按 IP 记的, 而
     * MockMvc 的直连地址恒为回环 —— 不分开的话这些用例会互相消耗对方的失败次数,
     * 表现为"单跑绿、一起跑红"。
     */
    @Test
    void aPairingCodeCanBeExchangedForCredentials() throws Exception {
        SimulatorPairingService.ProvisionResult provisioned = pairing.provisionSimulatorAccount("配对测试");
        assertNotNull(provisioned.pairingCode());

        JsonNode paired = json(mockMvc.perform(post("/api/simulator/pair")
                        .header("X-Forwarded-For", "10.10.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"" + provisioned.pairingCode() + "\"}"))
                .andReturn(), 200);
        assertEquals(provisioned.deviceId(), paired.path("deviceId").asText());
        assertEquals(provisioned.accountId(), paired.path("accountId").asText());
        assertTrue(paired.path("secret").asText().length() >= 32, "secret 是这次唯一的凭据, 不能短");
        assertFalse(paired.path("accessToken").asText().isBlank());

        // 码是一次性的: 同一段会话里配过之后设备已 ACTIVE, 旧码再也换不到东西
        mockMvc.perform(post("/api/simulator/pair")
                        .header("X-Forwarded-For", "10.10.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"" + provisioned.pairingCode() + "\"}"))
                .andExpect(result -> assertEquals(404, result.getResponse().getStatus(),
                        "配对码必须是一次性的, 否则一个泄露的码可以反复换取新 secret"));
    }

    /** 无效/过期/已用 —— 对外是同一件事。分开答等于告诉猜码的人"这个码的形状是对的"。 */
    @Test
    void aBadPairingCodeIsIndistinguishableFromAnExpiredOne() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/simulator/pair")
                        .header("X-Forwarded-For", "10.10.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"ZZZZZZ\"}")).andReturn();
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("配对码无效", json(result, 404).path("error").asText());
    }

    /**
     * 失败次数超限 → 429。
     *
     * <p>这条断言不是"限流能挡住穷举"(6 位码 + 10 分钟 TTL 本来就算不出来, 见
     * {@code PairingAttemptLimiter} 的类注释), 而是它确实在一次真实失败后被记上了 ——
     * 一个"写了但没接上"的限流器和没有限流器是完全一样的, 直到有人把它当成防线。
     */
    @Test
    void repeatedFailuresFromOneCallerAreThrottled() throws Exception {
        String ip = "10.10.0.99";
        for (int i = 0; i < 10; i++) {
            int attempt = i + 1;
            mockMvc.perform(post("/api/simulator/pair")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pairingCode\":\"AAAAA" + i + "\"}"))
                    .andExpect(result -> assertEquals(404, result.getResponse().getStatus(),
                            "第 " + attempt + " 次失败应当还是 404(限流门槛之内)"));
        }
        MvcResult throttled = mockMvc.perform(post("/api/simulator/pair")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"AAAAA0\"}")).andReturn();
        assertEquals(429, throttled.getResponse().getStatus(),
                "失败次数早就超了 —— 限流器没有真的被调用");
        assertNotNull(throttled.getResponse().getHeader("Retry-After"));

        // 另一个 IP 不受影响: 按 IP 分桶而不是全局一个计数器
        mockMvc.perform(post("/api/simulator/pair")
                        .header("X-Forwarded-For", "10.10.0.100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pairingCode\":\"AAAAA0\"}"))
                .andExpect(result -> assertEquals(404, result.getResponse().getStatus(),
                        "一个 IP 被限流不该让所有人都配不了对"));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** 用管理面签一把钥匙 —— 走真实端点而不是直接写库, 顺带把签发链路也验了。 */
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
        u.setUsername("access-test-" + UUID.randomUUID().toString().substring(0, 12));
        u.setPasswordHash("{noop}unused");
        u.setEmail(UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        u.setUserKind("HUMAN");
        return users.save(u).getId();
    }

    /**
     * 读响应体时<b>必须显式指定 UTF-8</b>。
     *
     * <p>{@code getContentAsString()} 的无参版本按 ISO-8859-1 解码, 于是每一个中文字段
     * 都会变成一串乱码 —— 而 MockMvc 的断言失败信息里只会显示"期望'我在' 实际'æå¨'",
     * 看起来像编码配置错了, 其实是断言侧读错了。这个坑在断言中文之前不会暴露。
     */
    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应码不对, 响应体: " + body(result));
        return objectMapper.readTree(body(result));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
