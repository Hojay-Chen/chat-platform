package com.luxera.companion.contracts.provision;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 注册成功之后的回执 —— 四个字段对应四个**不同**的东西, 它们是这次需求里被反复混淆的
 * 那一组, 所以在这里逐个点名:
 *
 * <ul>
 *   <li>{@code agentId} —— agent 平台标识这个 agent 个体的值({@code companions.id})。
 *       只在对接 agent 平台时用得上; 人念不出来, 也不该出现在聊天界面里。</li>
 *   <li>{@code chatAccountId} —— 它在聊天平台的账号 id({@code users.id})。原样回显,
 *       让调用方能核对"我登记的就是这个账号", 而不是只信自己的请求参数。</li>
 *   <li>{@code handle} —— **账号ID**, 人念得出来、报得出去的那个地址。它带
 *       {@code agent_} 前缀, 由 agent 平台铸, 永久不变。这是界面上要显示的那一个。</li>
 *   <li>{@code name} —— 名字。它可能重复(相近的描述会收敛到同一个名字), 所以它
 *       **不能**用来区分两个 agent —— 这正是账号ID 存在的原因。</li>
 * </ul>
 *
 * <p>{@code handle} 与 {@code name} 都可能为空(编译链偶发失败时), 所以两者都是可空的
 * 引用类型而不是空串: 调用方据此决定界面上画什么, 而不是画一个空白的灰块。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisteredAgent(
        String agentId,
        String chatAccountId,
        String handle,
        String name
) {
}
