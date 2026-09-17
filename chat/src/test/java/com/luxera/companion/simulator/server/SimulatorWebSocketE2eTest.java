package com.luxera.companion.simulator.server;

import com.luxera.chatframework.ChatPlatformTestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.contracts.dhcp.DhcpConstants;
import com.luxera.companion.contracts.dhcp.DhcpFrame;
import com.luxera.companion.contracts.dhcp.DhcpFrameType;
import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §63 端到端: 真实 WebSocket 连接 /ws/simulator, 走 CONNECT → AUTH → SUBSCRIBE → COMMAND。
 * 命令实际落库到 ConversationService(单进程过渡期: chat 侧与 DH 侧共享 JVM)。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = ChatPlatformTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorWebSocketE2eTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class PortConfig {
        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    SimulatorPairingService pairingService;
    @Autowired
    ConversationService conversationService;

    private final ObjectMapper mapper = new ObjectMapper();

    private String deviceId;
    private String secret;
    private String token;

    @BeforeEach
    void provisionAndPair() {
        var r = pairingService.provisionSimulatorAccount("E2E测试手机");
        var done = pairingService.completePairing(r.pairingCode());
        deviceId = done.deviceId();
        secret = done.secret();
        token = done.accessToken();
    }

    @AfterEach
    void cleanup() {
        try { pairingService.revokeDevice(deviceId); } catch (Exception ignored) {}
    }

    @ClientEndpoint
    public static class DhcpClient {
        public final BlockingQueue<DhcpFrame> frames = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper = new ObjectMapper();

        @OnMessage
        public void onMessage(String msg) {
            try {
                frames.add(mapper.readValue(msg, DhcpFrame.class));
            } catch (Exception ignored) {
            }
        }

        public DhcpFrame awaitType(DhcpFrameType type, long seconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < deadline) {
                DhcpFrame f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && f.type() == type) return f;
            }
            return null;
        }
    }

    private DhcpClient connect() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DhcpClient client = new DhcpClient();
        Session session = container.connectToServer(client, URI.create("ws://127.0.0.1:" + port + DhcpConstants.WS_PATH));
        return client;
    }

    private void send(Session session, DhcpFrame frame) throws Exception {
        session.getAsyncRemote().sendText(mapper.writeValueAsString(frame));
    }

    @Test
    void fullHandshakeAndCommandRoundTrip() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DhcpClient client = new DhcpClient();
        Session ws = container.connectToServer(client, URI.create("ws://127.0.0.1:" + port + DhcpConstants.WS_PATH));

        // 1. CONNECT → CONNECT_ACK
        send(ws, DhcpFrame.of(DhcpFrameType.CONNECT, "c1", mapper.createObjectNode().put("clientInfo", "e2e")));
        DhcpFrame ack = client.awaitType(DhcpFrameType.CONNECT_ACK, 5);
        assertNotNull(ack, "应收到 CONNECT_ACK");
        assertNotNull(ack.payload().get("sessionId"));

        // 2. AUTH → AUTH_SUCCESS
        var authPayload = mapper.createObjectNode()
                .put("deviceId", deviceId)
                .put("accessToken", token);
        send(ws, DhcpFrame.of(DhcpFrameType.AUTH, "a1", authPayload));
        DhcpFrame authOk = client.awaitType(DhcpFrameType.AUTH_SUCCESS, 5);
        assertNotNull(authOk, "应收到 AUTH_SUCCESS");
        assertTrue(authOk.payload().get("grantedScopes").size() > 0);

        // 3. SUBSCRIBE → READY
        var subPayload = mapper.createObjectNode();
        var topics = subPayload.putArray("topics");
        topics.add("chat.message.*");
        topics.add("phone.notification.*");
        send(ws, DhcpFrame.of(DhcpFrameType.SUBSCRIBE, "s1", subPayload));
        DhcpFrame ready = client.awaitType(DhcpFrameType.READY, 5);
        assertNotNull(ready, "应收到 READY");

        // 4. COMMAND chat.sendMessage → COMMAND_RESULT + 真实落库
        // chat 不知道"伴侣"是什么: 会话的对手方只是一个不透明 id
        String accountId = deviceIdAccountPairingAccount();
        String peerId = UUID.randomUUID().toString();
        Conversation conv = conversationService.create(accountId, peerId, "E2E会话", "E2E伴侣");
        var cmdPayload = mapper.createObjectNode()
                .put("command", "chat.sendMessage")
                .put("idempotencyKey", "e2e-idem-1")
                .set("args", mapper.createObjectNode()
                        .put("conversationId", conv.getId())
                        .put("senderType", "companion")
                        .put("content", "你好, 我是数字人手机")
                        .put("messageKind", "NORMAL")
                        .put("clientMessageId", "e2e-cmid-1"));
        send(ws, DhcpFrame.of(DhcpFrameType.COMMAND, "cmd-1", cmdPayload));
        DhcpFrame result = client.awaitType(DhcpFrameType.COMMAND_RESULT, 8);
        assertNotNull(result, "应收到 COMMAND_RESULT");
        assertTrue(result.payload().get("ok").asBoolean(), "命令应成功");
        String messageId = result.payload().get("data").get("messageId").asText();
        assertNotNull(messageId);

        // 落库校验
        List<Message> messages = conversationService.messages(conv.getId());
        assertTrue(messages.stream().anyMatch(m -> m.getId().equals(messageId)));

        // 5. PING → PONG
        send(ws, DhcpFrame.of(DhcpFrameType.PING, "p1", mapper.createObjectNode()));
        DhcpFrame pong = client.awaitType(DhcpFrameType.PONG, 5);
        assertNotNull(pong);

        ws.close();
    }

    /**
     * 缺陷 6.3.1 的回归测试 —— 走**真的 WS 面**, 不是直接调 service。
     *
     * <h2>为什么这条必须在 E2E 里补</h2>
     *
     * 上面那条用例也发了 COMMAND, 但它建会话时把 SIMULATOR 账号放在**真人那一侧**
     * ({@code create(accountId, peerId, …)}), 而且从来没发过 {@code chat.listConversations}。
     * 于是"Agent 用自己账号连上 WS 却看不到自己的会话"这个缺陷在整套测试里没有任何断言 ——
     * 它不报错, 只是返回空列表, Agent 于是安安静静地什么都不说。
     *
     * <p>这里把两个位置都摆正: 设备**绑到 agent 上**(一键创建/回填做的那一步),
     * 会话的 {@code user_id} 是**真人**。于是
     * {@code conversationService.list(accountId, companionId)} 必然为空 —— 它的判据
     * {@code Conversation.user_id} 永远是真人 —— 而 {@code listForMember} 找得到。
     * 两条断言同时写在这里, 就是为了让"哪一条查询是对的"在测试里也是明白的。
     */
    @Test
    void agentSeesItsOwnConversationThroughTheDispatcher() throws Exception {
        String companionId = UUID.randomUUID().toString();
        // 设备↔agent 的绑定是 AgentChatIdentity 唯一的判据, 也是这条命令能工作的前提
        pairingService.attachCompanion(deviceId, companionId);
        String accountId = deviceIdAccountPairingAccount();

        String human = UUID.randomUUID().toString();
        Conversation conv = conversationService.create(human, companionId, "E2E会话", "E2E伴侣");
        assertEquals(accountId, conv.getAgentAccountId(), "设备绑上之后新建的会话该记账号");

        // 「Agent 是参与者」这件事, 用旧的判据是**查不到**的 —— 这正是缺陷的形状
        assertTrue(conversationService.list(accountId, companionId).isEmpty(),
                "Conversation.user_id 永远是真人, 按它查必然为空 —— 这就是 6.3.1");

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DhcpClient client = new DhcpClient();
        Session ws = container.connectToServer(client, URI.create("ws://127.0.0.1:" + port + DhcpConstants.WS_PATH));

        send(ws, DhcpFrame.of(DhcpFrameType.CONNECT, "c1", mapper.createObjectNode().put("clientInfo", "e2e-list")));
        assertNotNull(client.awaitType(DhcpFrameType.CONNECT_ACK, 5), "应收到 CONNECT_ACK");

        send(ws, DhcpFrame.of(DhcpFrameType.AUTH, "a1", mapper.createObjectNode()
                .put("deviceId", deviceId).put("accessToken", token)));
        assertNotNull(client.awaitType(DhcpFrameType.AUTH_SUCCESS, 5), "应收到 AUTH_SUCCESS");

        var subPayload = mapper.createObjectNode();
        subPayload.putArray("topics").add("chat.message.*");
        send(ws, DhcpFrame.of(DhcpFrameType.SUBSCRIBE, "s1", subPayload));
        assertNotNull(client.awaitType(DhcpFrameType.READY, 5), "应收到 READY");

        JsonNode data = listConversations(client, ws, "cmd-list-1", companionId);
        JsonNode conversations = data.get("conversations");
        assertEquals(1, conversations.size(),
                "Agent 必须看得到自己参与的那一段 —— 空列表就是 6.3.1 复发");
        assertEquals(conv.getId(), conversations.get(0).get("id").asText());

        // args.companionId 只用来**过滤**, 不是归属判据: 别的 agent 的 id 过滤出空, 而不是越权
        JsonNode other = listConversations(client, ws, "cmd-list-2", UUID.randomUUID().toString());
        assertEquals(0, other.get("conversations").size(),
                "拿别人的 id 来问应当什么都问不到");

        ws.close();
    }

    /** 发一条 {@code chat.listConversations} 并等结果; 失败直接让用例炸在这条命令上。 */
    private JsonNode listConversations(DhcpClient client, Session ws, String requestId, String companionId)
            throws Exception {
        var payload = mapper.createObjectNode()
                .put("command", "chat.listConversations")
                .put("idempotencyKey", requestId + "-idem")
                .set("args", mapper.createObjectNode().put("companionId", companionId));
        send(ws, DhcpFrame.of(DhcpFrameType.COMMAND, requestId, payload));

        DhcpFrame result = client.awaitType(DhcpFrameType.COMMAND_RESULT, 8);
        assertNotNull(result, "应收到 COMMAND_RESULT");
        assertTrue(result.payload().get("ok").asBoolean(),
                "chat.listConversations 应当成功, 实际: " + result.payload());
        return result.payload().get("data");
    }

    @Test
    void authFailsWithBadToken() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DhcpClient client = new DhcpClient();
        Session ws = container.connectToServer(client, URI.create("ws://127.0.0.1:" + port + DhcpConstants.WS_PATH));

        send(ws, DhcpFrame.of(DhcpFrameType.CONNECT, "c1", null));
        client.awaitType(DhcpFrameType.CONNECT_ACK, 5);

        var authPayload = mapper.createObjectNode()
                .put("deviceId", deviceId)
                .put("accessToken", "BAD.TOKEN.VALUE");
        send(ws, DhcpFrame.of(DhcpFrameType.AUTH, "a2", authPayload));
        DhcpFrame err = client.awaitType(DhcpFrameType.ERROR, 5);
        assertNotNull(err, "坏令牌应收到 ERROR");
        assertEquals("AUTH_FAILED", err.payload().get("code").asText());
        ws.close();
    }

    private String deviceIdAccountPairingAccount() {
        return pairingService.getDeviceIfActive(deviceId)
                .map(SimulatorDevice::getAccountId)
                .orElseThrow();
    }
}
