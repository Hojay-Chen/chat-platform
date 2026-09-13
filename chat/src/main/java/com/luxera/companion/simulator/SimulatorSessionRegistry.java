package com.luxera.companion.simulator;

import com.luxera.companion.contracts.simulator.CapabilityType;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §4.2 Simulator Session Registry: 每绑定账号一个 Session 的运行时注册表。
 * 断线重连时按 accountId 复用/重建(Cursor 恢复同步, V10 §26)。
 * 双索引: accountId → session(账号维度) + sessionId → session(命令执行维度)。
 */
@Component
public class SimulatorSessionRegistry {

    private final ConcurrentHashMap<String, SimulatorSession> byAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SimulatorSession> bySessionId = new ConcurrentHashMap<>();

    /** 注册(或替换)某账号的会话 */
    public SimulatorSession register(SimulatorSession session) {
        byAccount.put(session.accountId(), session);
        bySessionId.put(session.sessionId(), session);
        return session;
    }

    public Optional<SimulatorSession> find(String accountId) {
        return Optional.ofNullable(byAccount.get(accountId));
    }

    /** 按账号或会话 id 查找(执行命令时用 sessionId, 管理时用 accountId) */
    public SimulatorSession require(String accountIdOrSessionId) {
        SimulatorSession byKey = byAccount.get(accountIdOrSessionId);
        return byKey != null ? byKey : bySessionId.get(accountIdOrSessionId);
    }

    /** 打开会话: 已存在则复用(重连), 不存在则新建并连接 */
    public SimulatorSession open(String accountId, java.util.Set<CapabilityType> scopes) {
        SimulatorSession existing = byAccount.get(accountId);
        if (existing != null) {
            existing.connect();
            return existing;
        }
        SimulatorSession session = new SimulatorSession(accountId, scopes);
        session.connect();
        byAccount.put(accountId, session);
        bySessionId.put(session.sessionId(), session);
        return session;
    }

    public void disconnect(String accountId) {
        SimulatorSession s = byAccount.get(accountId);
        if (s != null) s.disconnect();
    }

    public int size() {
        return byAccount.size();
    }
}
