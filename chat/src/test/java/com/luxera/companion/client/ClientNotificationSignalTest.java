package com.luxera.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.client.NotificationSignal;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationReadStateService;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2.2 §8.2.3 要求的三条性质 —— <b>每条消息一条信号 / 免打扰时没有信号 / 信号里没有正文</b>。
 *
 * <h2>为什么这三条要写在一个类里, 而且是这一组路由</h2>
 *
 * <p>因为它们是同一条链上的三个位置, 而这条链是
 * {@code 消息落库 → bumpOnMessage → 逐收件人判定免打扰 → 记一条信号 → 广播}。分开测会得到
 * 三条各自都能过、合起来不成立的断言:
 *
 * <ul>
 *   <li>只测"发了 5 条, 日志里有 5 条" —— 免打扰那一步就算被整个删掉, 它照样绿(测试里没有
 *       人被设置成免打扰)。</li>
 *   <li>只测"免打扰时没有信号" —— 把信号总线整个拆掉, 它也绿(都没有信号)。</li>
 *   <li>只测"信号里没有正文" —— 与上面两条完全无关, 它可以在信号一条都不产生时通过。</li>
 * </ul>
 *
 * <p>所以这里每条用例都从**真实的 HTTP 端点**出发(而不是直接调 service), 并且每条都同时
 * 断言"这一条铃响了/没响"和"这个事实在未读数上仍然成立"。
 *
 * <h2>为什么走真启动类</h2>
 *
 * <p>与 {@code ExternalChatAccessTest} 同一条理由: 客户端面的要害(过滤器放行、凭据换账号)
 * 落在 {@code com.luxera.companion.access} 与 {@code application.principal} 两个包里, 而测试
 * 启动类 {@code ChatPlatformTestApplication} 刻意不扫它们。少了这一层, 一个完全正确的信号
 * 链路会在真实调用上表现为"每个请求都是 Spring 的默认 403", 而单测全绿。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class)
@Import(ClientPlatformStub.class)
@AutoConfigureMockMvc
class ClientNotificationSignalTest {

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
    @Autowired
    ConversationReadStateService readStates;
    @Autowired
    NotificationSignalLog signalLog;

    // ── §8.2.3 第 1 条: 每条消息一条信号, 绝不聚合 ────────────────────────────

    /**
     * 五条消息 → 五条信号。<b>不是一条</b>。
     *
     * <p>"聚合"在这里是一个很容易被写出来、而且看起来很贴心的实现: "她连发五条, 何必响五次?"
     * 而它正是 §6.1 明文否掉的那一种 —— 铃声的条数就是消息的条数, 因为手机那边要靠
     * "响了几下"来决定"要不要现在打开看一眼"。聚合成一条之后, 五条与一条在手机上长得一模一样。
     *
     * <p>顺带钉住序号: 它们必须严格递增且互不相同。补发那条路是按
     * {@code signalId > lastAckSignalId} 判的, 序号重复会让"确认过的"与"还没到的"混在一起。
     */
    @Test
    void everyMessageProducesItsOwnSignal() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "铃铛测试");
        String human = newHuman();
        Conversation conv = conversations.create(human, agentId, "铃铛测试会话", "铃铛测试");
        String humanToken = humanToken(human);

        long before = signalLog.latestId(agentAccount);
        for (int i = 1; i <= 5; i++) {
            json(mockMvc.perform(post(clientPath(agentAccount) + "/messages")
                    .header("Authorization", "Bearer " + humanToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            Map.of("content", "第 " + i + " 条", "idempotencyKey", "n-" + i))))
                    .andReturn(), 201);
        }

        List<NotificationSignal> signals = signalLog.since(agentAccount, before);
        assertEquals(5, signals.size(),
                "五条消息必须是五条信号 —— 聚合成一条之后, 手机上'响了几下'不再等于'说了几句话'");

        Set<Long> ids = new HashSet<>();
        long previous = before;
        for (NotificationSignal s : signals) {
            assertTrue(ids.add(s.signalId()), "signalId 重复了: " + s);
            assertTrue(s.signalId() > previous,
                    "序号必须严格递增(补发按 signalId > lastAckSignalId 判), 实际: " + s);
            previous = s.signalId();
            assertEquals(conv.getId(), s.conversationId(),
                    "信号里的会话应当是这条消息真正落到的那一段");
            assertEquals(human, s.fromAccountId(),
                    "fromAccountId 必须是**那个人的聊天账号**, 与列表页上的 accountId 同一个值");
        }
    }

    /**
     * Agent 自己发的消息不给它自己响铃, 但对方那条铃照响。
     *
     * <p>这是"逐收件人"这件事在 1:1 里唯一能被观察到的地方: 一条消息的收件人集合是
     * "除发送者外的每个参与者"。写成"给会话里的每个人发"时, 自己发一句话会给自己也响一下
     * —— 而它看起来只是"多响了一下", 直到有人在手机上调了震动。
     */
    @Test
    void anAgentsOwnMessageDoesNotRingForTheAgentItself() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "自说自话");
        String human = newHuman();
        conversations.create(human, agentId, "自说自话会话", "自说自话");
        String agentToken = agentToken(agentId);

        long agentBefore = signalLog.latestId(agentAccount);
        long humanBefore = signalLog.latestId(human);
        json(mockMvc.perform(post(clientPath(human) + "/messages")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"是我在说话\"}"))
                .andReturn(), 201);

        assertTrue(signalLog.since(agentAccount, agentBefore).isEmpty(),
                "自己发的消息给自己响铃 —— 收件人集合写成'所有人'了");
        assertEquals(1, signalLog.since(human, humanBefore).size(),
                "对方必须听到这一下: 逐收件人不是'少发', 而是'发对人'");
    }

    // ── §8.2.3 第 2 条: 免打扰时没有信号 ─────────────────────────────────────

    /**
     * 开了免打扰: 一条信号都不产生, <b>而未读数照旧 +1</b>。
     *
     * <p>两个断言缺一不可, 而且第二个常被写漏。免打扰的语义是"铃不响", 不是"消息没来":
     * 她打开聊天软件时必须看到那三个红点, 否则免打扰就变成了丢消息 —— 而丢消息是这一整条
     * 链路上唯一不可接受的结果。
     *
     * <p>开关走的是真实的 {@code PUT .../notification}(§6.3 第 8 项), 于是这条用例同时钉住了
     * "§6.2 的第一层(平台侧判定)与它写的那一行是同一份数据": 写进去的免打扰, 必须在信号产生
     * 的那一刻被读到。
     */
    @Test
    void mutingStopsTheRingsButNotTheUnreadCount() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "免打扰测试");
        String human = newHuman();
        conversations.create(human, agentId, "免打扰会话", "免打扰测试");
        String humanToken = humanToken(human);
        String agentToken = agentToken(agentId);

        // 打开免打扰 —— 由 Agent 自己设置(就像真人点一下微信里的"消息免打扰")
        JsonNode setting = json(mockMvc.perform(put(clientPath(human) + "/notification")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"muted\":true,\"pinned\":false}"))
                .andReturn(), 200);
        assertTrue(setting.path("muted").asBoolean());
        assertTrue(setting.path("updatedAt").isTextual(),
                "updatedAt 必须是库里那一列的值 —— 空值说明写入没有 flush, 调用方读到了 null");
        assertTrue(readStates.isMutedNow(
                        conversations.listForMember(agentAccount).get(0).getId(), agentAccount),
                "PUT 写下的免打扰与信号产生处读的必须是同一行");

        long before = signalLog.latestId(agentAccount);
        for (int i = 1; i <= 3; i++) {
            json(mockMvc.perform(post(clientPath(agentAccount) + "/messages")
                    .header("Authorization", "Bearer " + humanToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"免打扰下的第 " + i + " 条\"}"))
                    .andReturn(), 201);
        }

        assertTrue(signalLog.since(agentAccount, before).isEmpty(),
                "免打扰生效中仍然产生了信号 —— 判定没有落在平台侧(§6.2 第一层)");

        JsonNode listed = json(mockMvc.perform(get("/api/client/conversations")
                        .header("Authorization", "Bearer " + agentToken))
                .andReturn(), 200);
        assertEquals(1, listed.size());
        assertEquals(3, listed.get(0).path("unreadCount").asInt(),
                "免打扰不该让消息'不算数' —— 她打开聊天软件时要看到这三个红点");
        assertTrue(listed.get(0).path("muted").asBoolean(),
                "列表页上的免打扰状态与信号判定必须来自同一次读取");
    }

    /**
     * 关掉之后立刻恢复: 判定的时刻是"消息到达的时刻", 不是"登录的时刻"。
     *
     * <p>这条防的是一个很自然的实现: 把 {@code mutedUntil} 在建立会话/登录时读一次, 之后一直
     * 用那个快照。它的一切表现都正常, 除了一个: 用户在消息到达前 1 毫秒关掉了免打扰, 那一次
     * 仍然不响。
     */
    @Test
    void unmutingTakesEffectOnTheVeryNextMessage() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "开关测试");
        String human = newHuman();
        conversations.create(human, agentId, "开关测试会话", "开关测试");
        String humanToken = humanToken(human);
        String agentToken = agentToken(agentId);

        json(mockMvc.perform(put(clientPath(human) + "/notification")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true,\"pinned\":false}"))
                .andReturn(), 200);
        long before = signalLog.latestId(agentAccount);
        send(humanToken, agentAccount, "免打扰期间");
        assertTrue(signalLog.since(agentAccount, before).isEmpty());

        json(mockMvc.perform(put(clientPath(human) + "/notification")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":false,\"pinned\":false}"))
                .andReturn(), 200);
        send(humanToken, agentAccount, "关掉之后");

        List<NotificationSignal> signals = signalLog.since(agentAccount, before);
        assertEquals(1, signals.size(),
                "关掉免打扰之后的下一条消息必须立刻响 —— 判定用的是'消息到达的时刻'");

        // 库里的那一行: muted=true ⇔ muted_until = 哨兵值, 这是 §6.2 两层表里第一层的唯一表示
        String conversationId = conversations.listForMember(agentAccount).get(0).getId();
        json(mockMvc.perform(put(clientPath(human) + "/notification")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true,\"pinned\":true}"))
                .andReturn(), 200);
        var row = readStates.stateOf(conversationId, agentAccount).orElseThrow();
        assertEquals(ConversationReadStateService.MUTED_FOREVER, row.getMutedUntil(),
                "muted=true 必须恰好落到 mutedUntil 的那个哨兵值上 —— 没有第二张表、第二个字段");
        assertFalse(row.getPinnedAt() == null, "pinned=true 落在 pinned_at 上, 与免打扰共用同一行");
    }

    // ── §8.2.3 第 3 条: 信号里没有正文 ──────────────────────────────────────

    /**
     * 正文里放一个独一无二的词, 然后确认它在信号的线上形状里<b>一个字都找不到</b>。
     *
     * <p>为什么用一个随机词而不是断言"没有 content 字段": 后者只能证明我们没有把它放进一个
     * 叫 {@code content} 的字段里。而"通知里没有正文"要防的是**任何一种**把内容带出去的方式
     * —— 塞进 reason、拼进 fromAccountId、放进将来加的某个新字段。随机词能抓到全部这些:
     * 只要它出现在那段 JSON 里的任何位置, 这条用例就红。
     */
    @Test
    void theSignalOnTheWireContainsNoTextFromTheMessage() throws Exception {
        String agentId = UUID.randomUUID().toString();
        String agentAccount = agentWithAccount(agentId, "正文测试");
        String human = newHuman();
        conversations.create(human, agentId, "正文测试会话", "正文测试");

        String secret = "秘密-" + UUID.randomUUID();
        long before = signalLog.latestId(agentAccount);
        send(humanToken(human), agentAccount, secret);

        List<NotificationSignal> signals = signalLog.since(agentAccount, before);
        assertEquals(1, signals.size());

        // 用应用自己那一个 ObjectMapper(它就是 ClientStreamRegistry 发帧用的那个):
        // 换一个序列化器就等于在测另一个东西
        String wire = objectMapper.writeValueAsString(signals.get(0));
        assertFalse(wire.contains(secret),
                "信号的线上形状里出现了消息正文 —— 通知里绝不能有内容。实际: " + wire);
        assertFalse(wire.contains("秘密"),
                "连正文的一部分都不该出现: " + wire);

        JsonNode shape = objectMapper.readTree(wire);
        List<String> keys = new ArrayList<>();
        shape.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("signalId", "conversationId", "fromAccountId", "raisedAt",
                        "unreadCount"), keys,
                "信号上多出了字段 —— 加之前先问一次: 它是内容吗? 实际: " + shape);
        assertEquals(1, shape.path("unreadCount").asInt(),
                "这条消息对收件人是一次未读, 而平台是在 AFTER_COMMIT 上读的自己那一行, "
                        + "所以这个数此刻必须是 1(见 NotificationSignal#unreadCount): " + shape);

        // 而正文确实在库里 —— 否则上面那条断言会因为"消息压根没发出去"而假绿
        JsonNode page = json(mockMvc.perform(get(clientPath(agentAccount) + "/messages")
                        .header("Authorization", "Bearer " + humanToken(human)))
                .andReturn(), 200);
        assertEquals(secret, page.path("messages").get(0).path("content").asText(),
                "正文必须能被 GET .../messages 读到 —— 它只是不在通知里");
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static String clientPath(String peerAccountId) {
        return "/api/client/conversations/" + peerAccountId;
    }

    private void send(String token, String peerAccountId, String content) throws Exception {
        json(mockMvc.perform(post(clientPath(peerAccountId) + "/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andReturn(), 201);
    }

    private String humanToken(String accountId) {
        return jwtUtil.generateToken(accountId, "signal-test-human", PrincipalType.HUMAN);
    }

    /** 铸一个 Agent 的聊天账号并把它绑到 {@code agentId} 上 —— 两条 §6.5 的绑定都做掉。 */
    private String agentWithAccount(String agentId, String displayName) {
        SimulatorPairingService.ProvisionResult r = pairing.provisionSimulatorAccount(displayName);
        pairing.attachCompanion(r.deviceId(), agentId);
        return r.accountId();
    }

    /** 走真实端点换令牌: 顺带把"钥匙只在 login 那一次被受理"这条链路也验了。 */
    private String agentToken(String agentId) throws Exception {
        String key = issueKey("信号测试", agentId);
        return json(mockMvc.perform(post("/api/client/login").header("X-Api-Key", key))
                .andReturn(), 200).path("token").asText();
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
        u.setUsername("signal-test-" + UUID.randomUUID().toString().substring(0, 12));
        u.setPasswordHash("{noop}unused");
        u.setEmail(UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        u.setUserKind("HUMAN");
        return users.save(u).getId();
    }

    /**
     * 读响应体必须显式指定 UTF-8 —— {@code getContentAsString()} 的无参版本按 ISO-8859-1
     * 解码, 于是每个中文字段都会变成乱码, 而断言失败信息看起来会像"编码配置错了"。
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
