package com.luxera.companion.contracts.simulator;

/**
 * V10 §4.3 Simulator Command(Command Pattern)。
 *
 * 所有外部客户端操作必须建模为 CapabilityCommand:
 * 命令携带 commandId(幂等键), 由 SimulatorCapability 执行。
 * 命令一旦提交, 重试同 commandId 不会产生重复副作用(由 Chat 侧幂等键保证)。
 */
public interface CapabilityCommand {

    /** 幂等键: 同 commandId 的重试不产生重复副作用 */
    String commandId();

    /** 目标能力类型(scope 校验用) */
    CapabilityType type();

    /** 会话 id(SimulatorClient 用其校验授权 scope) */
    String sessionId();
}
