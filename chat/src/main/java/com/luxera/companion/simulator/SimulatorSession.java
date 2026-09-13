package com.luxera.companion.simulator;

import com.luxera.companion.contracts.simulator.CapabilityType;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * V10 §4.2 Simulator Session: 绑定账号的模拟客户端运行时状态。
 *
 * 每个绑定账号对应一个 Session, 维护:
 * - connectionState: DISCONNECTED → CONNECTING → CONNECTED (V10 §4.2 状态机)
 * - deviceState:     BACKGROUND / FOREGROUND / LOCKED
 * - scopes:          该会话被授予的能力集合(最小权限)
 *
 * 状态迁移由 SimulatorClient 驱动; 外部系统只能看到 Session 视图, 不能直接修改。
 */
public class SimulatorSession {

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    public enum DeviceState { BACKGROUND, FOREGROUND, LOCKED }

    private final String sessionId;
    private final String accountId;
    private final Set<CapabilityType> scopes;
    private final Instant createdAt;
    private ConnectionState connectionState;
    private DeviceState deviceState;

    public SimulatorSession(String accountId, Set<CapabilityType> scopes) {
        this.sessionId = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.scopes = scopes == null || scopes.isEmpty() ? EnumSet.noneOf(CapabilityType.class) : EnumSet.copyOf(scopes);
        this.createdAt = Instant.now();
        this.connectionState = ConnectionState.DISCONNECTED;
        this.deviceState = DeviceState.BACKGROUND;
    }

    public String sessionId() { return sessionId; }

    public String accountId() { return accountId; }

    public ConnectionState connectionState() { return connectionState; }

    public DeviceState deviceState() { return deviceState; }

    public Instant createdAt() { return createdAt; }

    public Set<CapabilityType> scopes() { return Set.copyOf(scopes); }

    public boolean can(CapabilityType type) { return scopes.contains(type); }

    /** 连接状态迁移(仅 SimulatorClient 调用) */
    void connect() {
        if (connectionState == ConnectionState.DISCONNECTED) {
            connectionState = ConnectionState.CONNECTING;
        }
        connectionState = ConnectionState.CONNECTED;
    }

    void disconnect() { connectionState = ConnectionState.DISCONNECTED; }

    void toForeground() { deviceState = DeviceState.FOREGROUND; }

    void toBackground() { deviceState = DeviceState.BACKGROUND; }

    void lock() { deviceState = DeviceState.LOCKED; }

    @Override
    public String toString() {
        return "SimulatorSession{" + sessionId + ", account=" + accountId
                + ", conn=" + connectionState + ", device=" + deviceState + '}';
    }
}
