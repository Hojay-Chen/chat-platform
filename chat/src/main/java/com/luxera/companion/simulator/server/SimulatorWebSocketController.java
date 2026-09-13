package com.luxera.companion.simulator.server;

import com.luxera.companion.contracts.simulator.CapabilityType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.dhcp.AuthMessage;
import com.luxera.companion.contracts.dhcp.DhcpConstants;
import com.luxera.companion.contracts.dhcp.DhcpFrame;
import com.luxera.companion.contracts.dhcp.DhcpFrameType;
import com.luxera.companion.contracts.dhcp.SubscribeMessage;
import com.luxera.companion.simulator.SimulatorSessionRegistry;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §63 Simulator WebSocket 端点: {@code /ws/simulator}。
 *
 * <pre>
 * Client                          Server
 *  CONNECT    ─────────────────►   CONNECT_ACK(sessionId)
 *  AUTH       ─────────────────►  AUTH_SUCCESS(grantedScopes, expiresAt)
 *  SUBSCRIBE  ─────────────────►  READY
 *  (loop)
 *  EVENT      ◄─────────────────  server push (per-conn sequence, V10 §49)
 *  EVENT_ACK  ─────────────────►
 *  COMMAND    ─────────────────►  COMMAND_RESULT
 *  PING       ─────────────────►  PONG
 *  DISCONNECT ─────────────────►  (socket close)
 * </pre>
 *
 * 端点实例由 Spring 管理(经 SpringConfigurator 注入单例); WebSocket 会话状态在
 * {@link SimulatorConnection} 中。事件推送入口 {@link #publishEvent(String, com.fasterxml.jackson.databind.JsonNode)}。
 */
@Slf4j
@Component
@ServerEndpoint(value = DhcpConstants.WS_PATH)
public class SimulatorWebSocketController {

    private static final ConcurrentHashMap<String, SimulatorConnection> CONNECTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> DEVICE_TO_CONN = new ConcurrentHashMap<>();

    private static ObjectMapper objectMapper;
    private static SimulatorTokenService tokenService;
    private static SimulatorPairingService pairingService;
    private static SimulatorCommandDispatcher commandDispatcher;
    private static SimulatorSessionRegistry sessionRegistry;

    @Autowired
    public void setDependencies(ObjectMapper mapper, SimulatorTokenService tokens,
                                 SimulatorPairingService pairing, SimulatorCommandDispatcher dispatcher,
                                 SimulatorSessionRegistry registry) {
        objectMapper = mapper;
        tokenService = tokens;
        pairingService = pairing;
        commandDispatcher = dispatcher;
        sessionRegistry = registry;
    }

    @OnOpen
    public void onOpen(Session session) {
        String connId = "conn-" + UUID.randomUUID().toString().substring(0, 12);
        SimulatorConnection conn = new SimulatorConnection(connId, session);
        CONNECTIONS.put(connId, conn);
        log.debug("[WS] 连接建立: {}", connId);
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        SimulatorConnection conn = findBySession(session);
        if (conn == null) {
            return;
        }
        try {
            DhcpFrame frame = objectMapper.readValue(message, DhcpFrame.class);
            if (frame == null || frame.type() == null) {
                sendError(conn, null, "MALFORMED_FRAME", "帧缺失 type");
                return;
            }
            if (frame.version() == null || !DhcpConstants.PROTOCOL_VERSION.equals(frame.version())) {
                sendError(conn, frame.requestId(), "UNSUPPORTED_VERSION",
                        "仅支持 " + DhcpConstants.PROTOCOL_VERSION);
                return;
            }
            dispatch(conn, frame);
        } catch (Exception e) {
            log.warn("[WS] 帧处理异常: {}", e.getMessage());
            sendError(conn, null, "MALFORMED_FRAME", "解析错误: " + e.getMessage());
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        log.warn("[WS] 连接错误: {}", t.getMessage());
        cleanup(session);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        cleanup(session);
    }

    // ── 帧分发 ─────────────────────────────────────

    private void dispatch(SimulatorConnection conn, DhcpFrame frame) {
        switch (frame.type()) {
            case CONNECT -> handleConnect(conn, frame);
            case AUTH -> handleAuth(conn, frame);
            case SUBSCRIBE -> handleSubscribe(conn, frame);
            case COMMAND -> commandDispatcher.handle(conn, frame);
            case EVENT_ACK -> handleEventAck(conn, frame);
            case PING -> sendPong(conn, frame.requestId());
            case DISCONNECT -> closeQuietly(conn);
            default -> sendError(conn, frame.requestId(), "UNSUPPORTED_FRAME",
                    "不支持的帧类型: " + frame.type());
        }
    }

    private void handleConnect(SimulatorConnection conn, DhcpFrame frame) {
        conn.markConnected();
        var payload = objectMapper.createObjectNode().put("sessionId", conn.connectionId);
        send(conn, DhcpFrame.of(DhcpFrameType.CONNECT_ACK, frame.requestId(),
                conn.nextSequence.getAndIncrement(), payload));
    }

    private void handleAuth(SimulatorConnection conn, DhcpFrame frame) {
        if (conn.authed) {
            sendError(conn, frame.requestId(), "ALREADY_AUTHED", "已完成认证");
            return;
        }
        AuthMessage auth;
        try {
            auth = objectMapper.convertValue(frame.payload(), AuthMessage.class);
        } catch (Exception e) {
            auth = null;
        }
        if (auth == null || auth.deviceId() == null || auth.accessToken() == null) {
            sendError(conn, frame.requestId(), "INVALID_AUTH", "deviceId 与 accessToken 必填");
            return;
        }
        SimulatorTokenService.AuthClaims claims = tokenService.verify(auth.accessToken());
        if (claims == null || !claims.deviceId().equals(auth.deviceId())) {
            sendError(conn, frame.requestId(), "AUTH_FAILED", "令牌无效或不匹配 deviceId");
            return;
        }
        Optional<SimulatorDevice> deviceOpt = pairingService.getDeviceIfActive(auth.deviceId());
        if (deviceOpt.isEmpty()) {
            sendError(conn, frame.requestId(), "AUTH_FAILED", "设备不存在或非 ACTIVE");
            return;
        }
        SimulatorDevice device = deviceOpt.get();
        if (device.getTokenVersion() != claims.tokenVersion()) {
            sendError(conn, frame.requestId(), "AUTH_FAILED", "令牌版本已失效(设备已轮换/吊销)");
            return;
        }

        conn.authed = true;
        conn.deviceId = auth.deviceId();
        conn.accountId = device.getAccountId();

        // 注册 Simulator Session(让 COMMAND 路径能 scope 校验)
        sessionRegistry.open(device.getAccountId(), java.util.Set.of(
                com.luxera.companion.contracts.simulator.CapabilityType.READ_MESSAGES,
                com.luxera.companion.contracts.simulator.CapabilityType.SEND_MESSAGE,
                com.luxera.companion.contracts.simulator.CapabilityType.LIST_CONVERSATIONS,
                com.luxera.companion.contracts.simulator.CapabilityType.UPDATE_DELIVERY_STATUS));

        // 单设备单连接: 顶掉旧连接
        String old = DEVICE_TO_CONN.put(auth.deviceId(), conn.connectionId);
        if (old != null && !old.equals(conn.connectionId)) {
            SimulatorConnection stale = CONNECTIONS.remove(old);
            if (stale != null) closeQuietly(stale);
        }
        pairingService.updateLastSeen(auth.deviceId());

        var payload = objectMapper.createObjectNode()
                .putPOJO("grantedScopes", claims.scopes())
                .put("expiresAt", claims.expiresAt().toString());
        send(conn, DhcpFrame.of(DhcpFrameType.AUTH_SUCCESS, frame.requestId(),
                conn.nextSequence.getAndIncrement(), payload));
        log.info("[WS] AUTH 成功: device={}, account={}", conn.deviceId, conn.accountId);
    }

    private void handleSubscribe(SimulatorConnection conn, DhcpFrame frame) {
        if (!conn.authed) {
            sendError(conn, frame.requestId(), "NOT_AUTHED", "请先 AUTH");
            return;
        }
        SubscribeMessage sub;
        try {
            sub = objectMapper.convertValue(frame.payload(), SubscribeMessage.class);
        } catch (Exception e) {
            sub = null;
        }
        if (sub == null || sub.topics() == null || sub.topics().isEmpty()) {
            sendError(conn, frame.requestId(), "INVALID_SUBSCRIBE", "topics 不能为空");
            return;
        }
        conn.subscribedTopics = Set.copyOf(sub.topics());
        var payload = objectMapper.createObjectNode().put("status", "READY");
        send(conn, DhcpFrame.of(DhcpFrameType.READY, frame.requestId(),
                conn.nextSequence.getAndIncrement(), payload));
        log.info("[WS] SUBSCRIBE: device={}, topics={}", conn.deviceId, sub.topics());
    }

    private void handleEventAck(SimulatorConnection conn, DhcpFrame frame) {
        // V10 §49: ack 推进游标(R3 接 client_sync_cursor 持久化)
        log.debug("[WS] EVENT_ACK: device={}, frame={}", conn.deviceId, frame.requestId());
    }

    private void sendPong(SimulatorConnection conn, String requestId) {
        var payload = objectMapper.createObjectNode()
                .put("serverTimeMs", System.currentTimeMillis());
        send(conn, DhcpFrame.of(DhcpFrameType.PONG, requestId,
                conn.nextSequence.getAndIncrement(), payload));
    }

    // ── 发送/清理 ─────────────────────────────────

    private void send(SimulatorConnection conn, DhcpFrame frame) {
        if (conn == null || !conn.wsSession.isOpen()) return;
        try {
            conn.wsSession.getAsyncRemote().sendText(objectMapper.writeValueAsString(frame));
        } catch (IOException e) {
            log.warn("[WS] 发送失败: {}", e.getMessage());
        }
    }

    private void sendError(SimulatorConnection conn, String requestId, String code, String message) {
        if (conn == null || !conn.wsSession.isOpen()) return;
        var payload = objectMapper.createObjectNode().put("code", code).put("message", message);
        try {
            conn.wsSession.getAsyncRemote().sendText(objectMapper.writeValueAsString(
                    DhcpFrame.of(DhcpFrameType.ERROR, requestId, null, payload)));
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(SimulatorConnection conn) {
        try {
            conn.wsSession.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"));
        } catch (IOException ignored) {
        }
        cleanup(conn.wsSession);
    }

    private SimulatorConnection findBySession(Session session) {
        for (SimulatorConnection c : CONNECTIONS.values()) {
            if (c.wsSession.equals(session)) return c;
        }
        return null;
    }

    private void cleanup(Session session) {
        SimulatorConnection conn = findBySession(session);
        if (conn == null) return;
        CONNECTIONS.remove(conn.connectionId);
        if (conn.deviceId != null) {
            DEVICE_TO_CONN.remove(conn.deviceId, conn.connectionId);
        }
    }

    // ── 事件推送静态入口(供 chat 平台业务侧调用) ────

    /** 向所有订阅了该 topic 的已认证连接推送 EVENT 帧(V10 §49 per-connection FIFO) */
    public static void publishEvent(String topic, com.fasterxml.jackson.databind.JsonNode envelope) {
        for (SimulatorConnection conn : CONNECTIONS.values()) {
            if (!conn.authed || !conn.wsSession.isOpen()) continue;
            if (!matchesTopic(topic, conn.subscribedTopics)) continue;
            long seq = conn.nextSequence.getAndIncrement();
            DhcpFrame frame = DhcpFrame.of(DhcpFrameType.EVENT, null, seq, envelope);
            try {
                conn.wsSession.getAsyncRemote().sendText(staticMapper().writeValueAsString(frame));
            } catch (IOException e) {
                // 发送失败静默; 断线由重连+resume 兜底
            }
        }
    }

    /** 按设备推送(命令结果/定向事件) */
    public static void sendToDevice(String deviceId, DhcpFrame frame) {
        SimulatorConnection conn = byDevice(deviceId);
        if (conn != null) {
            try {
                conn.wsSession.getAsyncRemote().sendText(staticMapper().writeValueAsString(frame));
            } catch (IOException ignored) {
            }
        }
    }

    /** 当前已认证设备数(诊断) */
    public static int activeConnections() {
        int n = 0;
        for (SimulatorConnection c : CONNECTIONS.values()) {
            if (c.authed) n++;
        }
        return n;
    }

    static SimulatorConnection byDevice(String deviceId) {
        String connId = DEVICE_TO_CONN.get(deviceId);
        return connId == null ? null : CONNECTIONS.get(connId);
    }

    private static ObjectMapper staticMapper() {
        return objectMapper;
    }

    private static boolean matchesTopic(String topic, Set<String> subs) {
        if (subs == null || subs.isEmpty()) return false;
        for (String sub : subs) {
            if (sub.equals(topic)) return true;
            if (sub.endsWith(".*") && topic.startsWith(sub.substring(0, sub.length() - 1))) return true;
        }
        return false;
    }
}