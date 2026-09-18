package com.luxera.companion.contracts.client;

import java.time.LocalDateTime;

/**
 * V2.2 §6.5 —— 账号绑定引导的回执: <b>一次调用同时给出这条关系的两端</b>。
 *
 * <h2>为什么两端要一起回</h2>
 *
 * <p>§6.5 的时序图里有两条 Note, 它们说的是同一件事的两半:
 *
 * <pre>
 *   AP-&gt;CP: POST /api/client/provision（为 agent 建聊天账号）
 *   CP--&gt;AP: accountId = "agent_m3k9"
 *   Note over AP: 同时拿到用户的 accountId = "user_8f3a"
 *   AP-&gt;AP: 建 PersonObject(用户) + Relationship(用户 ↔ agent)
 *   AP-&gt;AP: RelationshipGraph.bind("user_8f3a" → PersonObject(用户))
 * </pre>
 *
 * <p>Agent 平台要建的那条绑定是"<b>某个聊天账号 ↔ 她心里的某个人物对象</b>"。它需要两端:
 * 被绑定的那个账号(新铸的 Agent 账号)与它对应的那个人(发起这次创建的用户)。
 *
 * <p>如果这个响应只回新账号, 对面就得**再问一次**"这个 agent 归谁" —— 而那个问题的答案
 * 就在这次调用的调用方身上, 平台本来就知道。让对面为了一个已知的值多走一次网络, 换来的
 * 只有"两次调用之间世界变了"这一类竞态。
 *
 * <h2>{@code relationshipType} 原样回显, 不解释</h2>
 *
 * <p>它是 Agent 平台的概念(用户与 Agent 的关系: 朋友/家人/同事…), 本平台只是把它从调用方
 * 手里接过来、放进同一次事务的编排里, 再原样交回去。聊天平台不认识它, 也不该认识 ——
 * 它不影响任何一条聊天链路的判定。
 *
 * @param requestId            幂等锚点原样回显 —— 重试时对面靠它确认"这是同一次意图的答复"
 * @param accountId            新铸出来的<b>Agent 聊天账号</b> id。这就是 §6.5 里
 *                             {@code "agent_m3k9"} 那个位置的值。
 * @param ownerAccountId       发起这次创建的**真人的聊天账号** id(§6.5 里的
 *                             {@code "user_8f3a"})。绑定关系的另一端。
 * @param relationshipType     关系类型, 原样回显
 * @param displayName          Agent 的展示名
 * @param agentId              Agent 在 Agent 平台的 id({@code companions.id})。
 *                             与 {@code accountId} 是**两个平台的两种 id**, 按设计永不互换。
 * @param pairingCode          Agent 侧的设备用来换凭据的配对码; 重试命中一台**已经配对过**的
 *                             设备时为 null —— 那不是失败(见 {@code SimulatorPairingService})
 * @param pairingCodeExpiresAt 码的过期时刻, 与上面同真同假
 */
public record ProvisionedChatAccount(
        String requestId,
        String accountId,
        String ownerAccountId,
        String relationshipType,
        String displayName,
        String agentId,
        String pairingCode,
        LocalDateTime pairingCodeExpiresAt
) {
}
