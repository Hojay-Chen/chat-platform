package com.luxera.companion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.contracts.client.NotificationSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.util.List;
import java.util.Map;

/**
 * V2.2 §6.6 —— {@code WS /api/client/stream}: <b>「手机响了」的那条长连接</b>。
 *
 * <h2>这条连接上跑的东西只有一样: 铃声</h2>
 *
 * <pre>
 *   客户端 → 服务端: PING（心跳）、ACK（"第 N 条我收到了"）
 *   服务端 → 客户端: READY、NOTIFICATION、SESSION_EXPIRED、ERROR
 * </pre>
 *
 * <p>没有任何一种帧能承载消息正文 —— 唯一的"有内容"的字段是
 * {@link NotificationSignal}, 而它本身没有可承载正文的地方
 * (见那个类型的说明: 四个分量全是 id / 序号 / 时刻)。要看说了什么, 客户端必须再发一次
 * {@code GET /api/client/conversations/{accountId}/messages}。这条性质让"通知"与"正文"在
 * <b>协议层</b>就是两条路, 而不是靠客户端自觉不去读某个字段。
 *
 * <h2>握手里就完成鉴权与补发</h2>
 *
 * <p>顺序是固定的, 而且每一步的位置都有原因:
 *
 * <ol>
 *   <li><b>解令牌</b> —— 令牌从查询参数 {@code ?token=} 来。WebSocket 的握手在浏览器里
 *       发不了自定义头(那是浏览器的 API 限制, 不是设计选择), 所以令牌只能走查询串。
 *       解不开就发一条 {@code SESSION_EXPIRED} 再关 —— 直接关连接的话, 客户端只会看到
 *       "连接被拒绝了", 而它没有任何办法分辨那是令牌过期还是服务器挂了。</li>
 *   <li><b>先登记连接, 再读信号日志</b>。反过来的话, 在这两个动作之间到达的一条消息会
 *       <b>两头落空</b>: 广播时我们还不在表里, 补发时快照已经取完 —— 而"少响一下"是查不出来
 *       的。这个顺序最多带来一次可去重的重复(signalId 在账号内单调递增, 客户端丢掉
 *       {@code <= lastAckSignalId} 的那些即可)。用一次可去重的重复, 换掉一次不可察觉的丢失。</li>
 *   <li><b>补发只发信号, 不发正文</b> —— {@code NotificationSignalLog.since} 的返回类型里没有
 *       第二种东西可选。</li>
 * </ol>
 *
 * <h2>整段补发期间持有这把连接的锁</h2>
 *
 * <p>不持有的话, 一条恰好在这一刻到达的广播会插到补发的中间 —— 客户端会看到
 * {@code NOTIFICATION(7)} 之后才看到 {@code NOTIFICATION(5, 6)}, 而一个按序号推进
 * {@code lastAckSignalId} 的客户端遇到 7 就会把 5 和 6 判成"已经确认过的", 于是它们永远
 * 不会响。{@code ClientStreamRegistry.send} 里加的是同一把锁(JSR-356 的
 * {@code getBasicRemote()} 不允许并发调用), 于是这里是重入而不是新加一层。
 *
 * <h2>为什么这个类不是 {@code @Controller}</h2>
 *
 * <p>它是 JSR-356 端点, 由 Servlet 容器实例化 —— Spring 只负责提供那一个实例(见
 * {@link ClientStreamConfigurator})。
 */
@Slf4j
@Component
@ServerEndpoint(value = "/api/client/stream", configurator = ClientStreamConfigurator.class)
public class ClientStreamEndpoint {

    /** 令牌放在这个查询参数上 —— 浏览器的 WebSocket API 发不了自定义请求头。 */
    public static final String PARAM_TOKEN = "token";
    /** 客户端上次确认到第几条。不传 = 0 = "一条都没确认过"。 */
    public static final String PARAM_LAST_ACK = "lastAckSignalId";

    /** 会话属性里的键: 这条连接对应的注册表句柄 —— 关闭时用它注销。 */
    private static final String PROP_CONNECTION = "client.connection";

    private final ClientPrincipalResolver resolver;
    private final NotificationSignalLog signalLog;
    private final ClientStreamRegistry registry;
    private final ObjectMapper objectMapper;

    public ClientStreamEndpoint(ClientPrincipalResolver resolver,
                                NotificationSignalLog signalLog,
                                ClientStreamRegistry registry,
                                ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.signalLog = signalLog;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        String token = param(session, PARAM_TOKEN);
        String accountId;
        try {
            accountId = resolver.resolve("Bearer " + (token == null ? "" : token)).accountId();
        } catch (ClientApiException e) {
            // 一条可以解释的失败, 而不是一次静默的断连 —— 见类注释第 1 步。
            log.info("[客户端面] WS 握手被拒: {}", e.getMessage());
            try {
                session.getBasicRemote().sendText(
                        objectMapper.writeValueAsString(
                                ClientStreamFrame.sessionExpired(e.getMessage())));
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized"));
            } catch (Exception ignored) {
                // 对面已经走了。握手失败到这里就结束了, 没有别的事可做。
            }
            return;
        }

        long lastAck = parseAck(param(session, PARAM_LAST_ACK));
        ClientStreamRegistry.Connection conn = registry.register(accountId, session);
        session.getUserProperties().put(PROP_CONNECTION, conn);

        synchronized (conn.lock()) {
            registry.send(conn, ClientStreamFrame.ready());
            List<NotificationSignal> missed = signalLog.since(accountId, lastAck);
            for (NotificationSignal signal : missed) {
                registry.send(conn, ClientStreamFrame.notification(signal));
            }
            if (!missed.isEmpty()) {
                log.info("[客户端面] 账号 {} 重连, 补发 {} 条通知信号(不含正文)", accountId, missed.size());
            }
        }
    }

    /**
     * 客户端发来的两种帧。
     *
     * <p>解析失败答 {@code ERROR} 而**不**关连接: 一条读不懂的帧不该把一条正在正常工作的
     * 长连接弄断, 而对面拿到一条 {@code ERROR} 就知道自己发错了什么。
     */
    @OnMessage
    public void onMessage(String text, Session session) {
        ClientStreamRegistry.Connection conn = connectionOf(session);
        if (conn == null) return;
        ClientStreamFrame frame;
        try {
            frame = objectMapper.readValue(text, ClientStreamFrame.class);
        } catch (Exception e) {
            registry.send(conn, ClientStreamFrame.error("帧解析失败"));
            return;
        }
        String type = frame.type() == null ? "" : frame.type().trim().toUpperCase(java.util.Locale.ROOT);
        switch (type) {
            case ClientStreamFrame.TYPE_PING -> registry.send(conn, ClientStreamFrame.pong());
            case ClientStreamFrame.TYPE_ACK -> conn.ack(frame.lastAckSignalId());
            // 客户端不该往这条连接上发别的东西(它没有可以发的东西)。仍然答一条 ERROR 而不是
            // 静静忽略: 一个"发了但什么都没发生"的协议是最难对接的。
            default -> registry.send(conn, ClientStreamFrame.error("不支持的类型: " + type));
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        registry.unregister(connectionOf(session));
    }

    /**
     * 连接上的异常 —— 摘掉连接, 不往上抛。
     *
     * <p>最常见的那一种(对面关掉了窗口/断网)在这里与"程序出错"长得一样, 而它们要的处理
     * 也确实是同一个: 这条连接不能用了。真要区分的话, 日志里有堆栈。
     */
    @OnError
    public void onError(Session session, Throwable error) {
        registry.unregister(connectionOf(session));
        log.debug("[客户端面] WS 连接 {} 出错: {}", session.getId(), error.toString());
    }

    private static ClientStreamRegistry.Connection connectionOf(Session session) {
        Object conn = session.getUserProperties().get(PROP_CONNECTION);
        return conn instanceof ClientStreamRegistry.Connection c ? c : null;
    }

    private static String param(Session session, String name) {
        Map<String, List<String>> params = session.getRequestParameterMap();
        List<String> values = params == null ? null : params.get(name);
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 读不懂的 {@code lastAckSignalId} 按 0 处理(补发全部还留着的) —— 比整条握手持掉好。 */
    private static long parseAck(String raw) {
        if (raw == null) return 0;
        try {
            long v = Long.parseLong(raw);
            return Math.max(v, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
