package com.luxera.companion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.config.JwtUtil;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.contracts.client.NotificationSignal;
import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §6.6 端到端: <b>真实 WebSocket 连接</b> {@code /api/client/stream}。
 *
 * <h2>为什么这条链路非要在真端口上跑一次</h2>
 *
 * <p>因为这个仓库里已经栽过两次同一个坑: 端点类写得完全正确, 而它<b>没有注册进容器</b>
 * —— JSR-356 的 {@code @ServerEndpoint} 要靠 {@code ServerEndpointExporter} 按类显式交给
 * Servlet 容器({@code WebSocketConfig} 那一行), 少一个类的症状是那条路径上永远 404, 而
 * 直接调 controller/service 的测试全绿。{@code SimulatorWebSocketController} 的注释里记着
 * 这同一个坑, 而 V2.2 又往同一个导出器上加了第二个端点 —— 正是最容易漏一次的地方。
 *
 * <h2>这里能断到、而单测断不到的两件事</h2>
 *
 * <ol>
 *   <li><b>渲染之后的字节里没有正文</b> —— 信号类型上有没有 content 字段是一件事, 真正发出去
 *       的那串 JSON 里有没有那些字是另一件事(别名、派生字段、拼进 reason, 都能让两者不同)。
 *       所以这里存的是<b>原始报文</b>, 而不是反序列化之后的对象。</li>
 *   <li><b>重连补发的是信号, 不是正文</b> —— §6.6 那句话在
 *       {@code NotificationSignalLog.since} 的返回类型里就已经成立, 但它成立的方式是"补发帧
 *       里只有信号", 而那要在线路上看。</li>
 * </ol>
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.luxera.chatserver.ChatPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ClientPlatformStub.class)
class ClientStreamE2eTest {

    /** 与 {@code SimulatorWebSocketE2eTest} 逐字相同: 明确要一个真的 Tomcat, WS 才有容器可用。 */
    @TestConfiguration
    static class PortConfig {
        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory();
        }
    }

    private static final String PATH = "/api/client/stream";

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    UserRepository users;
    @Autowired
    ConversationService conversations;
    @Autowired
    SimulatorPairingService pairing;
    @Autowired
    ClientStreamRegistry registry;

    // ── 握手里就绪 + 每一条消息一条帧 ────────────────────────────────────────

    /**
     * 连上就有 READY, 然后说一句话就响一下 —— 而那一帧的字节里没有那句话。
     *
     * <p>READY 这一步不是装饰: 它是"鉴权已经过了、补发已经发完了"的分界。少了它, 客户端只能
     * 靠"等一会儿没别的帧了"来判断自己连上了没有 —— 而那是一个没有正确答案的判断。
     */
    @Test
    void aConnectedClientGetsReadyAndThenOneRingPerMessage() throws Exception {
        Fixture f = new Fixture("小满");

        SignalClient client = new SignalClient();
        Session ws = connect(client, f.humanToken, null);
        assertNotNull(client.awaitFrame(ClientStreamFrame.TYPE_READY, 5L),
                "连上之后的第一帧必须是 READY —— 它是'鉴权已过、补发已发完'的分界");

        String secret = "只有这条消息里才有的词-" + UUID.randomUUID();
        conversations.addMessage(f.conversation.getId(), "companion", secret, "ws-1");

        ClientStreamFrame frame = client.awaitFrame(ClientStreamFrame.TYPE_NOTIFICATION, 5L);
        assertNotNull(frame, "对方说了一句话, 这条长连接上应当收到一条通知帧");
        NotificationSignal signal = frame.signal();
        assertNotNull(signal);
        assertEquals(f.conversation.getId(), signal.conversationId());
        assertEquals(f.agentAccount, signal.fromAccountId(),
                "fromAccountId 与列表页上的 accountId 必须是同一个命名空间里的同一个值");
        assertTrue(signal.signalId() > 0, "序号从 1 开始: 0 在协议里表示'一条都没确认过'");

        // 线路上真的没有正文 —— 看的是原始报文, 不是反序列化之后的对象
        for (String raw : client.rawFrames) {
            assertFalse(raw.contains(secret),
                    "发出去的帧里出现了消息正文: " + raw);
        }
        JsonNode shape = client.mapper.readTree(client.lastRaw);
        List<String> keys = new ArrayList<>();
        shape.path("signal").fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("signalId", "conversationId", "fromAccountId", "raisedAt",
                        "unreadCount"), keys,
                "信号上多出了一个键 —— 加之前先问一次: 它是内容吗? 实际: " + shape);
        assertEquals(1, signal.unreadCount(),
                "她还没读过这一条, 红点上就是 1 —— 这个数由平台给(契约 1.0.2), "
                        + "不许由客户端自己累加, 因为它必须包含'免打扰期间照涨的那些'");

        ws.close();
    }

    /**
     * 断开期间的铃声要被补上, 而且只补<b>没确认过的那些</b>; 补发帧里同样没有正文。
     *
     * <p>三条断言合起来说清楚了一件事: 补发是一个**按序号推进的区间**, 不是"把最近的说一遍"。
     * 少了"只补没确认的", 客户端每次重连都会把已经听过的铃再听一遍 —— 而那在用户那里的表现是
     * 手机每隔一会儿就震一下。
     */
    @Test
    void reconnectReplaysExactlyTheUnacknowledgedSignalsAndNoContent() throws Exception {
        Fixture f = new Fixture("重连测试");

        // ① 连上, 收到两条, 确认到第二条为止, 然后关掉
        SignalClient first = new SignalClient();
        Session ws = connect(first, f.humanToken, null);
        assertNotNull(first.awaitFrame(ClientStreamFrame.TYPE_READY, 5L));

        conversations.addMessage(f.conversation.getId(), "companion", "第一条", "ws-r1");
        conversations.addMessage(f.conversation.getId(), "companion", "第二条", "ws-r2");
        ClientStreamFrame one = first.awaitFrame(ClientStreamFrame.TYPE_NOTIFICATION, 5L);
        ClientStreamFrame two = first.awaitFrame(ClientStreamFrame.TYPE_NOTIFICATION, 5L);
        assertNotNull(one);
        assertNotNull(two);
        long ackedId = two.signal().signalId();
        assertTrue(one.signal().signalId() < ackedId, "两条消息必须是两条序号递增的信号");

        ws.getBasicRemote().sendText(
                first.mapper.writeValueAsString(
                        new ClientStreamFrame(ClientStreamFrame.TYPE_ACK, null, null, ackedId)));
        ws.close();
        assertTrue(awaitConnectionCount(f.human, 0), "关闭之后注册表里不该还留着这条连接");

        // ② 断开期间又来了一条 —— 它必须被补上
        String offlineSecret = "断线期间的那句话-" + UUID.randomUUID();
        conversations.addMessage(f.conversation.getId(), "companion", offlineSecret, "ws-r3");

        // ③ 带 lastAckSignalId 重连: READY 之后<b>只有</b>那一条
        SignalClient second = new SignalClient();
        Session reconnected = connect(second, f.humanToken, ackedId);
        assertNotNull(second.awaitFrame(ClientStreamFrame.TYPE_READY, 5L));
        ClientStreamFrame replayed = second.awaitFrame(ClientStreamFrame.TYPE_NOTIFICATION, 5L);
        assertNotNull(replayed, "断线期间的那条消息没有被补发 —— 那一下铃永远丢了");
        assertTrue(replayed.signal().signalId() > ackedId,
                "补发的必须是没确认过的那些, 实际: " + replayed.signal());

        // 而且只有这一条: 已经确认过的两条不该再来一遍
        assertNull(second.pollFrame(ClientStreamFrame.TYPE_NOTIFICATION, 1L),
                "已经确认过的信号被重复补发了 —— 手机每隔一会儿就会多震一下");

        // ④ 补发的帧里也没有正文
        for (String raw : second.rawFrames) {
            assertFalse(raw.contains(offlineSecret),
                    "补发帧里出现了正文 —— §6.6 要求补发的只是通知信号: " + raw);
        }

        reconnected.close();
    }

    // ── 令牌与心跳 ────────────────────────────────────────────────────────────

    /**
     * 令牌不对时发一条 {@code SESSION_EXPIRED} 再关, <b>而不是静默断连</b>。
     *
     * <p>这条区分是有用的: 静默断连让客户端只看到"连接被拒绝了", 而它没有任何办法分辨那是
     * 令牌过期还是服务器挂了 —— 前者要它重新 login, 后者要它退避重连。
     */
    @Test
    void aBadOrMissingTokenIsExplainedBeforeTheSocketIsClosed() throws Exception {
        SignalClient noToken = new SignalClient();
        connect(noToken, null, null);
        ClientStreamFrame expired = noToken.awaitFrame(ClientStreamFrame.TYPE_SESSION_EXPIRED, 5L);
        assertNotNull(expired, "没有令牌时必须先解释一句再关");
        assertNotNull(expired.reason(), "要说明白是为什么 —— 理由就在这个字段里");

        SignalClient badToken = new SignalClient();
        connect(badToken, "NOT.A.REAL.TOKEN", null);
        assertNotNull(badToken.awaitFrame(ClientStreamFrame.TYPE_SESSION_EXPIRED, 5L));

        // 反过来: 一个合法令牌不该收到任何 SESSION_EXPIRED
        Fixture f = new Fixture("令牌测试");
        SignalClient good = new SignalClient();
        Session ws = connect(good, f.humanToken, null);
        assertNotNull(good.awaitFrame(ClientStreamFrame.TYPE_READY, 5L));
        assertNull(good.pollFrame(ClientStreamFrame.TYPE_SESSION_EXPIRED, 1L),
                "合法令牌收到了 SESSION_EXPIRED —— 握手校验认错了人");
        ws.close();
    }

    /** 心跳要有人应。一个发了没回应的心跳, 客户端只能靠超时判断死活。 */
    @Test
    void pingIsAnsweredWithPongAndAnUnknownFrameWithAnError() throws Exception {
        Fixture f = new Fixture("心跳测试");
        SignalClient client = new SignalClient();
        Session ws = connect(client, f.humanToken, null);
        assertNotNull(client.awaitFrame(ClientStreamFrame.TYPE_READY, 5L));

        ws.getBasicRemote().sendText(
                client.mapper.writeValueAsString(
                        new ClientStreamFrame(ClientStreamFrame.TYPE_PING, null, null, 0)));
        assertNotNull(client.awaitFrame(ClientStreamFrame.TYPE_PONG, 5L), "PING 必须有 PONG");

        ws.getBasicRemote().sendText(
                client.mapper.writeValueAsString(
                        new ClientStreamFrame("WHATEVER", null, null, 0)));
        ClientStreamFrame error = client.awaitFrame(ClientStreamFrame.TYPE_ERROR, 5L);
        assertNotNull(error, "读不懂的帧要答一条 ERROR, 而不是静默忽略");

        // 答完 ERROR 之后连接还活着 —— 一条读不懂的帧不该把一条正常工作的长连接弄断
        ws.getBasicRemote().sendText(
                client.mapper.writeValueAsString(
                        new ClientStreamFrame(ClientStreamFrame.TYPE_PING, null, null, 0)));
        assertNotNull(client.awaitFrame(ClientStreamFrame.TYPE_PONG, 5L),
                "连接被一条 ERROR 弄断了 —— 它应当继续工作");
        ws.close();
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────────

    /** 一段"真人 ↔ 一个已有聊天账号的 Agent"的会话, 以及真人的令牌。 */
    private final class Fixture {
        final String agentAccount;
        final String human;
        final Conversation conversation;
        final String humanToken;

        Fixture(String displayName) {
            String agentId = UUID.randomUUID().toString();
            SimulatorPairingService.ProvisionResult r = pairing.provisionSimulatorAccount(displayName);
            pairing.attachCompanion(r.deviceId(), agentId);
            this.agentAccount = r.accountId();
            this.human = newHuman();
            this.conversation = conversations.create(human, agentId, displayName + "的会话", displayName);
            this.humanToken = jwtUtil.generateToken(human, "ws-e2e-human", PrincipalType.HUMAN);
        }
    }

    @ClientEndpoint
    public static class SignalClient {

        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final BlockingQueue<ClientStreamFrame> frames = new LinkedBlockingQueue<>();
        /** <b>原始报文</b>: "线路上有没有正文"只能看它, 不能看反序列化之后的对象。 */
        final List<String> rawFrames = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String lastRaw;

        @OnMessage
        public void onMessage(String msg) {
            lastRaw = msg;
            rawFrames.add(msg);
            try {
                frames.add(mapper.readValue(msg, ClientStreamFrame.class));
            } catch (Exception ignored) {
                // 读不懂的帧留在 rawFrames 里, 而"该收到的那一帧"会由超时断言出来
            }
        }

        ClientStreamFrame awaitFrame(String type, long seconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < deadline) {
                ClientStreamFrame f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && type.equals(f.type())) return f;
            }
            return null;
        }

        ClientStreamFrame pollFrame(String type, long seconds) throws InterruptedException {
            return awaitFrame(type, seconds);
        }
    }

    private Session connect(SignalClient client, String token, Long lastAckSignalId) throws Exception {
        StringBuilder url = new StringBuilder("ws://127.0.0.1:").append(port).append(PATH);
        if (token != null) url.append("?token=").append(token);
        if (lastAckSignalId != null) {
            url.append(token == null ? "?" : "&").append("lastAckSignalId=").append(lastAckSignalId);
        }
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        return container.connectToServer(client, URI.create(url.toString()));
    }

    /** 连接的摘除是异步的(close 之后容器才回调 {@code @OnClose}), 所以这里等一小会儿。 */
    private boolean awaitConnectionCount(String accountId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (registry.connectionCount(accountId) == expected) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private String newHuman() {
        User u = new User();
        u.setUsername("ws-e2e-" + UUID.randomUUID().toString().substring(0, 12));
        u.setPasswordHash("{noop}unused");
        u.setEmail(UUID.randomUUID().toString().substring(0, 8) + "@test.local");
        u.setUserKind("HUMAN");
        return users.save(u).getId();
    }
}
