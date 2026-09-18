package com.luxera.companion.client;

import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.contracts.client.ProvisionedChatAccount;
import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * V2.2 §6.5 —— {@code POST /api/client/provision}: <b>为 Agent 建一个聊天账号, 并把这条关系
 * 的两端一起交回去</b>。
 *
 * <h2>它和 {@code AgentFriendProvisioningService} 是一件事的两个方向</h2>
 *
 * <pre>
 *   POST /api/agent-friends   （真人发起, JWT）   聊天平台建账号 → 通知 Agent 平台建 agent
 *   POST /api/client/provision（Agent 平台发起, 管理密钥）  Agent 平台已有 agent → 聊天平台建账号
 * </pre>
 *
 * <p>两条路都落到同一个原语上: {@link SimulatorPairingService#provisionSimulatorAccount} ——
 * "铸一个 {@code user_kind='SIMULATOR'} 的账号 + 一台 PAIRING 设备"。<b>刻意复用而不是另写
 * 一遍</b>: 账号ID 一旦发出去就不可回收, 而幂等锚点、重试的四条分支(PAIRING/过期/ACTIVE/
 * REVOKED 各自怎么答)都长在那一个方法里。抄一份出来, 等于让同一个账号 id 有两个铸造点,
 * 而它们各自看都是对的。
 *
 * <h2>为什么响应里必须带上 {@code ownerAccountId}</h2>
 *
 * <p>见 {@link ProvisionedChatAccount} 的说明: §6.5 的时序图里, Agent 平台拿到新账号之后
 * 立刻要建 {@code Relationship(用户 ↔ agent)} —— 而那条关系的另一端(用户的聊天账号)就在这次
 * 调用的入参里, 平台本来就知道。让它为了一个已知的值多走一次网络, 换来的只有"两次调用之间
 * 世界变了"这一类竞态。
 *
 * <h2>一个已知的边界: {@code conversation_account_binding} 不在这里</h2>
 *
 * <p>V2.2 §7.2 那张表({@code human_id / chat_account_id / person_id / bind_reason / bound_at})
 * 属于 <b>Agent 平台的库</b> —— {@code person_id} 是"她心里的那个人物对象", 而那个概念只存在
 * 于仓 2。本仓这边"哪个聊天账号 ↔ 哪个 agent"的持久记录一直是
 * {@code simulator_devices.companion_id}, 这里沿用同一列({@code attachCompanion})。
 * 两个平台各记一半, 而它们在同一个事务边界之外 —— 这正是 §6.5 把它画成一次跨平台调用序列的
 * 原因, 也是这条链上唯一需要人工对账的地方({@code SimulatorDeviceReconciler})。
 */
@Slf4j
@Service
public class ClientProvisioningService {

    private final SimulatorPairingService pairing;
    private final ConversationService conversations;
    private final UserRepository users;

    public ClientProvisioningService(SimulatorPairingService pairing,
                                     ConversationService conversations,
                                     UserRepository users) {
        this.pairing = pairing;
        this.conversations = conversations;
        this.users = users;
    }

    /**
     * 铸账号, 并在给了 {@code agentId} 的时候把绑定与通讯录一并做掉。
     *
     * <p>{@code agentId} 可空: §6.5 那次调用的时刻, Agent 平台可能**还没**把 agent 建出来
     * (它只是先要一个账号, 就像 {@code AgentFriendProvisioningService} 的第一步)。给了就绑,
     * 没给就只建账号 —— 之后随时可以用同一把 requestId 再调一次补上绑定, 因为幂等锚点在设备表上,
     * 而重试命中的是同一行。
     *
     * @param requestId 幂等锚点, 由调用方生成 —— 与 {@code messages.client_message_id} 同一先例。
     *                  不给它的话, "响应丢了"的每一次重试都会多一个再也用不上的 SIMULATOR 账号。
     */
    public ProvisionedChatAccount provision(String requestId, String displayName,
                                            String ownerAccountId, String relationshipType,
                                            String agentId) {
        if (!StringUtils.hasText(requestId)) {
            throw ClientApiException.badRequest("requestId 不能为空",
                    "它是幂等键: 同一次意图请复用同一个值, 重试才不会铸出第二个账号");
        }
        if (StringUtils.hasText(ownerAccountId) && !users.existsById(ownerAccountId)) {
            // 宁可当场 400, 也不要回一个对面拿去建 Relationship 时才发现不存在的 id ——
            // 那时它已经在自己的库里写了一半。
            throw ClientApiException.badRequest("ownerAccountId 不存在: " + ownerAccountId,
                    "它是发起这次创建的**真人**的聊天账号 id");
        }

        SimulatorPairingService.ProvisionResult account =
                pairing.provisionSimulatorAccount(displayName, requestId.trim());

        if (StringUtils.hasText(agentId)) {
            // 与 AgentFriendProvisioningService 的第四步同一个动作、同一列。绑定失败要抛 ——
            // 静默吞掉的话这次调用报告"成功", 而这个 agent 之后永远说不了话(它不知道自己该用
            // 哪个账号)。重试是安全的: 同一个 requestId 命中同一台设备。
            pairing.attachCompanion(account.deviceId(), agentId);
            if (StringUtils.hasText(ownerAccountId)) {
                // 通讯录: 绑了 agent 却没有会话, 它就只是一个"存在的账号", 不会出现在任何人的
                // 列表里(与 AgentFriendProvisioningService 的第四步同一条理由)。
                conversations.ensureConversation(ownerAccountId, agentId, displayName);
            }
        }

        log.info("[客户端面] 已为 {} 铸出聊天账号 {}(设备 {})",
                StringUtils.hasText(agentId) ? "agent " + agentId : "调用方指定的 Agent",
                account.accountId(), account.deviceId());
        return new ProvisionedChatAccount(
                requestId.trim(),
                account.accountId(),
                ownerAccountId,
                relationshipType,
                displayName,
                agentId,
                account.pairingCode(),
                account.pairingCodeExpiresAt());
    }
}
