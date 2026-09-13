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
