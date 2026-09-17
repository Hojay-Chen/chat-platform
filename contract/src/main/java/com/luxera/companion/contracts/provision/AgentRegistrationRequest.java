package com.luxera.companion.contracts.provision;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 注册一个 agent 的全部输入 —— 与仓 2 {@code POST /api/v1/openapi/agents} 的请求体同形。
 *
 * <h2>字段的归属</h2>
 *
 * <ul>
 *   <li>{@code description} / {@code persona} —— 二选一, 人格从哪来。{@code persona} 是
 *       已编译好的人格, 与仓 2 的 {@code Persona} 同形(见 {@link PersonaJson})。</li>
 *   <li>{@code chatAccountId} —— <b>聊天平台的账号 id</b>({@code users.id})。它不是
 *       agent 的 id, 也不是 agent 的账号ID; 它回答"这个 agent 用哪个聊天账号说话"。
 *       三个 id 的分工见仓 2 {@code Companion} 里 {@code chat_account_id} 那一列的注释。</li>
 *   <li>{@code relationshipType} —— 关系的初始类型; 为空时服务端按人格里的声明、
 *       再退回 {@code friend}。</li>
 *   <li>{@code ownerUserId} —— <b>这个 agent 归哪个真人所有</b>。为空时 agent 归调用者
 *       自己, 那是第三方程序自助创建的正常形状。</li>
 * </ul>
 *
 * <h2>{@code ownerUserId} 是一道闸门, 不是一个便利参数</h2>
 *
 * 服务端只在调用方被标记为可信({@code openapi_clients.can_act_for_users})时才认它, 否则
 * 直接 403。少了这道闸, 任何持 {@code sap_} key 的程序都能往任意用户的通讯录里塞一个
 * 归他所有、他自己却删不掉的 agent —— 那是"代建"这个词最坏的一种解释。
 *
 * <p>这条记录本身**不带那个判断**: 它只把调用方想做的事说清楚。放行与否是服务端的事,
 * 而"服务端在哪一行放行"必须能被单独读出来 —— 藏在一个 record 的构造里是最难审计的写法。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRegistrationRequest(
        String description,
        PersonaJson persona,
        String relationshipType,
        String chatAccountId,
        String ownerUserId
) {
}
