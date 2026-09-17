package com.luxera.companion.conversation;

/**
 * 「这个 Agent 在聊天平台上用哪个账号说话」—— 会话模块要问的唯一一个问题。
 *
 * <h2>为什么是一个接口, 而不是直接注入那张设备表</h2>
 *
 * 答案在**本仓的哪张表知道这件事**上: 只有 {@code simulator_devices} 同时记着
 * {@code companion_id}(agent 平台的)与 {@code account_id}(本仓的聊天账号)。
 *
 * <p>而那是 {@code simulator.server} 包的表。让 {@code conversation} 直接 import 它的仓储,
 * 就成了一条"会话模块依赖仿真设备模块"的实线 —— 而仿真设备是 DH(数字人)侧的概念, 会话是
 * 人与人/人与 Agent 的通用概念。这条线一旦连上, 之后每一次动设备表都要先想一遍会话模块。
 *
 * <p>声明成接口之后依赖方向反过来: 会话模块只说"我需要知道这个", 由知道的那一侧来实现。
 * 调用方永远拿到的是同一份答案, 而它来自哪张表对本包不可见。
 *
 * <h2>为什么调用方必须能接受 null</h2>
 *
 * 因为**没有账号的 Agent 是正常状态, 不是错误**: 账号ID 那次改造之前建出来的 Agent 全都
 * 没有, 而它们的历史会话至今仍在正常使用。没有账号时返回 {@code null}, 由调用方回退到
 * {@code companionId} —— 那正是这次迁移之前一直用的值, 也是迁移期间两侧必须保持一致的那个值。
 *
 * <p>换句话说: 这个方法返回 null 时, 系统的行为必须与"身份分离这个需求从未存在过"时**逐字
 * 相同**。迁移能分步做、能在任何一步停下来, 靠的就是这条性质。
 */
public interface AgentChatIdentity {

    /**
     * @param companionId agent 平台的 agent id({@code companions.id})
     * @return 它在聊天平台的账号 id({@code users.id}); 没有账号时 {@code null}
     */
    String chatAccountIdOf(String companionId);
}
