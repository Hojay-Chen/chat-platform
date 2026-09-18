package com.luxera.companion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.client.ClientStreamFrame;
import com.luxera.companion.contracts.client.NotificationSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §6.6 —— {@code WS /api/client/stream} 上"谁在线"的那张表。
 *
 * <h2>一个账号可以有多条连接</h2>
 *
 * <p>因为真人就是这样的: 网页开着、手机上也开着。所以这里是
 * {@code 账号 → 一组连接}, 而不是一对一。发通知时<b>每一条连接各收一份</b> —— 聚合成
 * 一份再广播是另一种形式的"聚合多条消息成一条通知", 而 §6.1 明确说那是错的。
 *
 * <h2>连接是连接, 令牌是令牌</h2>
 *
 * <p>这里存的是已经通过 {@link ClientPrincipalResolver} 的<b>账号 id</b>, 不是令牌本身。
 * 令牌过期之后连接仍然在(它已经是过去一次性校验的产物), 而这正是真实聊天软件的行为:
 * 令牌到期不该让已经打开的窗口黑掉 —— 下一次请求会 401, 客户端据此重新 login。
 * 要主动踢人(账号被注销 / 令牌被吊销)时走 {@link #expire}, 它是显式的。
 *
 * <h2>发送是同步的, 而且加了锁</h2>
 *
 * <p>JSR-356 的 {@code Session.getBasicRemote()} <b>不允许并发调用</b>(并发时抛
 * {@code IllegalStateException}, 而那个异常会从一条无害的通知里冒出来)。通知的发送线程是
 * 消息落库那条请求线, 而一条会话上的两条消息完全可能来自两个不同的请求线程 —— 所以这里的
 * 锁不是防御性的, 是必需的。
 *
 * <p>发送失败只记日志并把那条连接摘掉: 一个已经断掉的连接不该让"给其他人发通知"这件事失败
 * ({@code ClientNotificationService} 的调用点在一次消息落库之后, 那里没有任何东西可以
 * 因为通知失败而回滚 —— 消息已经是事实了)。
 */
@Slf4j
@Component
public class ClientStreamRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Connection>> byAccount = new ConcurrentHashMap<>();

    public ClientStreamRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 连接建立时登记 —— 返回的对象是这条连接的句柄, 断线时用它注销。 */
    public Connection register(String accountId, Session session) {
        Connection conn = new Connection(accountId, session);
        byAccount.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>())
                .put(conn.connectionId(), conn);
        return conn;
    }

    public void unregister(Connection conn) {
        if (conn == null) return;
        Map<String, Connection> conns = byAccount.get(conn.accountId());
        if (conns == null) return;
        conns.remove(conn.connectionId());
        if (conns.isEmpty()) {
            // 账号级别也清掉, 否则这张表会随着"曾经连接过的账号数"一直涨 —— 它看起来只是
            // 一堆空 Map, 但那是每一个用过这个客户端的账号一行。
            byAccount.remove(conn.accountId(), conns);
        }
    }

    /** 在线连接数 —— 诊断与测试用。 */
    public int connectionCount(String accountId) {
        Map<String, Connection> conns = byAccount.get(accountId);
        return conns == null ? 0 : conns.size();
    }

    /**
     * 一条信号发给这个账号的<b>每一条</b>连接。
     *
     * <p>失败的连接从表里摘掉, 但不影响其它连接: 一次通知里最坏的情况是"手机那条连接已经死
     * 了", 而它不该让桌面那条也收不到。
     */
    public void notify(String accountId, NotificationSignal signal) {
        Map<String, Connection> conns = byAccount.get(accountId);
        if (conns == null || conns.isEmpty()) {
            // 没人在线是**正常的**: 信号仍然被 NotificationSignalLog 记下, 她重连时会补到。
            // 这里不落任何日志 —— 每一条离线消息都打一行日志, 只会把真正的错误淹掉。
            return;
        }
        ClientStreamFrame frame = ClientStreamFrame.notification(signal);
        for (Connection conn : new ArrayList<>(conns.values())) {
            send(conn, frame);
        }
    }

    /** 这个会话的令牌已经不能用了 —— 通知它重新 login, 然后关掉。 */
    public void expire(String accountId, String reason) {
        Map<String, Connection> conns = byAccount.get(accountId);
        if (conns == null) return;
        for (Connection conn : new ArrayList<>(conns.values())) {
            send(conn, ClientStreamFrame.sessionExpired(reason));
        }
    }

    /**
     * 一条帧发给一条连接。失败即摘除。
     *
     * <p>序列化失败与发送失败在这里是同一件事: 这条连接收不到这一帧了。区别只在于前者说明
     * 我们自己写了一个发不出去的帧(那是 bug, 要在日志里看得见), 所以它单独打一条 warn。
     */
    public void send(Connection conn, ClientStreamFrame frame) {
        String text;
        try {
            text = objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("[客户端面] 帧序列化失败({}), 这一条不发: {}", frame.type(), e.toString());
            return;
        }
        try {
            synchronized (conn.lock()) {
                conn.session().getBasicRemote().sendText(text);
            }
        } catch (Exception e) {
            log.debug("[客户端面] 连接 {} 发送失败, 摘除: {}", conn.connectionId(), e.toString());
            unregister(conn);
        }
    }

    /**
     * 一条已经通过校验的连接。
     *
     * <p>{@code lastAckSignalId} 存在**连接**上而不是账号上: 她手机上的那个客户端收到了 30 条、
     * 网页上那个只收到了 28 条, 重连时各自要补的条数不一样。放在账号上, 后连上来的那个会把
     * 另一个的进度抹掉。
     */
    public static final class Connection {

        private final String connectionId;
        private final String accountId;
        private final Session session;
        private final Object lock = new Object();
        private final AtomicLong lastAckSignalId = new AtomicLong(0);

        Connection(String accountId, Session session) {
            this.connectionId = session.getId();
            this.accountId = accountId;
            this.session = session;
        }

        public String connectionId() {
            return connectionId;
        }

        public String accountId() {
            return accountId;
        }

        public Session session() {
            return session;
        }

        Object lock() {
            return lock;
        }

        public long lastAckSignalId() {
            return lastAckSignalId.get();
        }

        /** 只前进, 不后退 —— 一条乱序到达的旧 Ack 不该让补发范围变大。 */
        public void ack(long signalId) {
            lastAckSignalId.accumulateAndGet(signalId, Math::max);
        }
    }
}
