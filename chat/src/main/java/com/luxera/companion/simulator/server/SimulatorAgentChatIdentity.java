package com.luxera.companion.simulator.server;

import com.luxera.companion.conversation.AgentChatIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgentChatIdentity} 的唯一实现: 从 {@code simulator_devices} 的绑定关系回答"这个
 * Agent 用哪个聊天账号说话"。
 *
 * <h2>为什么答案就在本仓, 而不是去问 Agent 平台</h2>
 *
 * 因为 {@code simulator_devices} 这一行**本来就是把两个 id 绑在一起的那条记录**
 * ({@code companion_id} = agent 平台的, {@code account_id} = 本仓的), 而它一直是本仓在写
 * ({@code provisionSimulatorAccount} 铸账号、{@code attachCompanion} 绑定)。
 *
 * <p>曾经有一个更"对称"的设计: 会话创建时调 Agent 平台的内部接口, 让对面告诉我们这个
 * Agent 的聊天账号。它被否掉的理由不是复杂度, 而是**它会凭空多出一个运行时依赖**: 对面只要
 * 重启一次, "打开一段新对话"这个动作就会失败 —— 而聊天平台打开一段对话, 本来完全不需要
 * Agent 平台在场(那段会话的内容可以之后再由它填)。
 *
 * <p>跨平台调用应该只在**信息真的在对面**时发生。这一条不是: 绑定关系是本仓写的, 就在本仓。
 *
 * <h2>取不到时返回 null, 而不是抛</h2>
 *
 * 见 {@link AgentChatIdentity} 的说明 —— 没有账号是正常状态。这里额外多说一句的是
 * **不要**在这个方法里兜底成"那就用 companionId 吧": 回退是调用方的事, 而且调用方可能
 * 需要知道"到底是哪一个"(写进 {@code conversations.agent_account_id} 的那一列必须是账号,
 * 不能是一个伪装成账号的 agent id)。这里只回答事实。
 */
@Slf4j
@Component
public class SimulatorAgentChatIdentity implements AgentChatIdentity {

    private final SimulatorDeviceRepository devices;

    public SimulatorAgentChatIdentity(SimulatorDeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    @Transactional(readOnly = true)
    public String chatAccountIdOf(String companionId) {
        if (companionId == null || companionId.isBlank()) return null;
        SimulatorDevice device = devices
                .findFirstByCompanionIdOrderByCreatedAtAsc(companionId)
                .orElse(null);
        if (device == null) return null;
        if (device.getAccountId() == null || device.getAccountId().isBlank()) {
            // 账号列为空的设备行不该存在(它是 NOT NULL 的), 但不值得为它抛异常 ——
            // 静默回退到 companionId 和"这个 Agent 还没有账号"是同一种结果。
            log.warn("[Agent身份] 设备 {} 绑在 agent {} 上, 但它的 account_id 是空的 —— 按无账号处理",
                    device.getDeviceId(), companionId);
            return null;
        }
        return device.getAccountId();
    }
}
