package com.luxera.companion.contracts.provision;

/**
 * 仓 1 → 仓 2 —— 「以某个**聊天账号**的名义注册一个 agent」。
 *
 * <h2>为什么这个端口在 contract 里, 而它的实现与调用都住在仓 1</h2>
 *
 * 因为这条线上的**线上形状**是两个平台的共同约定: 仓 2 的
 * {@code POST /api/v1/openapi/agents} 既服务第三方程序, 也服务聊天平台。把请求/响应
 * 记录放在这里, 是让"什么叫创建一个 agent"在两个仓里只有**一份**书面定义。各写一份
 * 迟早会漂, 而漂的表现是两个仓都编译得过、运行时才 400。
 *
 * <h2>为什么叫「注册」而不是「创建」</h2>
 *
 * 因为**账号先于 agent 存在**: 走到这一步时, 调用方手里已经有一个 {@code chatAccountId}
 * (聊天平台先建好了聊天账号 —— 这是需求 ④ 规定的顺序)。这个调用做的是把那个账号
 * **登记**给一个新 agent, 而不是凭空造一个用户。
 *
 * <p>agent 的元数据里因此留下 {@code chat_account_id}。那一列同时是**幂等键**:
 * 同一个聊天账号重复注册, 拿到的是同一个 agent, 不会出现第二个。这是重试能安全的原因 ——
 * "建了但响应丢了"这种最常见的失败, 重试一次就回到正常路径上。
 *
 * <h2>实现与调用都在仓 1, 那它为什么不干脆是仓 1 的一个内部接口</h2>
 *
 * 因为它描述的是**跨平台**的一次调用, 而跨平台的形状必须与仓 2 对外的那个 openAPI 端点
 * 完全一致 —— 那个端点是公开的、被第三方程序调用的。放进 contract 是把"它就是那个公开
 * 端点"这件事写成结构, 而不是写成一句注释。
 */
public interface AgentRegistrationPort {

    /**
     * 注册一个 agent。
     *
     * <p>两种给人格的方式二选一, 与仓 2 openAPI 的约定一致: 给 {@code description}
     * 走平台的编译链(自然语言 → 结构化人格), 或给 {@code persona} 直传已编译好的。
     *
     * <p><b>失败要抛, 不要返回 null。</b>这条链路的失败是"用户点了创建但没建成", 调用方
     * 必须知道, 才能去补偿(见 {@code AgentFriendProvisioningService} 里对 device 的收尾)。
     * 一个 null 会被下游当成"建好了但没名字", 把一次明确的失败变成半个成功。
     */
    RegisteredAgent register(AgentRegistrationRequest request);
}
