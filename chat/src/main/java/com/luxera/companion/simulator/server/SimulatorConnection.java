package com.luxera.companion.simulator.server;

import javax.websocket.Session;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 WebSocket 连接对象(从 SimulatorWebSocketController 中提取, 供 Dispatcher 与
 * 事件发布静态方法访问)。不可序列化, 仅进程内使用。
 */
public class SimulatorConnection {

    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    public final String connectionId;
    public final Session wsSession;
    public final AtomicLong nextSequence = new AtomicLong(1);

    // 认证后字段
    public String deviceId;
    public String accountId;
    public Set<String> subscribedTopics = Set.of();
    public boolean authed = false;
    public State state = State.CONNECTING;

    public SimulatorConnection(String connectionId, Session wsSession) {
        this.connectionId = connectionId;
        this.wsSession = wsSession;
    }

    public void markConnected() {
        this.state = State.CONNECTED;
    }

    public boolean isConnected() {
        return wsSession != null && wsSession.isOpen() && state == State.CONNECTED;
    }
}