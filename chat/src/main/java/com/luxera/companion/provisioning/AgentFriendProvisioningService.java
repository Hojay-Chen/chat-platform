package com.luxera.companion.provisioning;

import com.luxera.companion.conversation.ConversationService;
import com.luxera.companion.contracts.provision.AgentRegistrationPort;
import com.luxera.companion.contracts.provision.AgentRegistrationRequest;
import com.luxera.companion.contracts.provision.PersonaJson;
import com.luxera.companion.contracts.provision.RegisteredAgent;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 一键创建 Agent 好友 —— 需求 ④ 的那条编排, 全部住在聊天平台这一侧。
 *
 * <h2>顺序是需求规定的, 不是实现挑的</h2>
 *
 * 用户的原话是"聊天平台先创建聊天账号, 然后调用 agent 平台的 openAPI 创建 agent 并把
 * 聊天账号ID 传递过去, 然后 agent 平台的 agent 元数据能够记录下这个"。所以四步是:
 *
 * <ol>
 *   <li><b>建聊天账号</b> —— {@link SimulatorPairingService#provisionSimulatorAccount}。
 *       一个 {@code users} 行({@code user_kind='SIMULATOR'})+ 一台 PAIRING 设备。
 *       账号是**先于** agent 存在的: agent 的元数据里要记的那个 id, 此刻就在手里了。</li>
 *   <li><b>登记 agent</b> —— {@link AgentRegistrationPort#register}, 把上一步的
 *       {@code users.id} 作为 {@code chatAccountId} 传过去, 同时把
 *       {@code ownerUserId} 指定为**发起这次操作的真人**。归来时对面已经有
 *       {@code companions.chat_account_id} 那一列的值。</li>
 *   <li><b>回绑</b> —— {@code simulator_devices.companion_id = agentId}。这一步是本仓
 *       自己的记录: 到此为止"哪个聊天账号 ↔ 哪个 agent"在两个平台上各有一份。</li>
 *   <li><b>进通讯录</b> —— {@link ConversationService#ensureConversation} 建出那个会话,
 *       好友才会出现在用户的列表里(那正是"一键创建**好友**"的"好友"两字)。</li>
 * </ol>
 *
 * <h2>这个类刻意不是 {@code @Transactional}</h2>
 *
 * 它的第一步和第二步之间隔着**一次跨平台 HTTP 调用**。把整个编排包进一个数据库事务里
 * 会有两个后果, 两个都很糟:
 *
 * <ul>
 *   <li>事务期间占着一条数据库连接等对面(秒级), 而对面超时的上限是
 *       {@code app.agent-platform.timeout-ms}。并发几个一键创建就能把连接池抽干。</li>
 *   <li><b>更要命的是补偿会被回滚掉。</b>下面第三步失败时要吊销设备, 而那次吊销如果
 *       挂在同一个事务上, 异常往外一抛就一起回滚了 —— 于是"补偿"成了一个只在日志里
 *       发生过的事, 库里那台半死的设备原样留着。</li>
 * </ul>
 *
 * <p>所以每一步各自成事务(由各自的 service 保证), 编排层只负责顺序与补偿。
 */
@Slf4j
@Service
public class AgentFriendProvisioningService {

    private final SimulatorPairingService pairing;
    private final AgentRegistrationPort agentRegistration;
    private final ConversationService conversations;

    public AgentFriendProvisioningService(SimulatorPairingService pairing,
                                          AgentRegistrationPort agentRegistration,
                                          ConversationService conversations) {
        this.pairing = pairing;
        this.agentRegistration = agentRegistration;
        this.conversations = conversations;
    }

    /**
     * 走完四步。
     *
     * @param ownerUserId <b>发起这次操作的真人</b> —— 由控制器从 JWT 取, 绝不从请求体取。
     *                    见 {@link AgentFriendController} 上的说明: 这个字段决定 agent 归谁所有,
     *                    让调用方自己填就是把"往别人通讯录里塞 agent"的钥匙交出去。
     * @param requestId   幂等锚点, 由调用方生成。同一个 requestId 重放两次不会产生
     *                    第二个聊天账号, 也不会产生第二个 agent(两步各自幂等, 见各自的实现)。
     */
    public ProvisionedAgentFriend create(String ownerUserId, String requestId,
                                         String description, PersonaJson persona,
                                         String relationshipType) {
        // ── 第一步 + 第二步: 账号(先) ────────────────────────────────────────
        // displayName 此刻只能是个猜测: agent 的名字要到第三步编译完人格才知道。传 persona
        // 的场合可以直接拿到; 只给 description 的场合只好先留空, 由第四步补上。
        String provisionalName = personaName(persona);
        SimulatorPairingService.ProvisionResult account =
                pairing.provisionSimulatorAccount(provisionalName, requestId);

        // ── 第三步: agent(后), 失败则补偿 ───────────────────────────────────
        RegisteredAgent agent;
        try {
            agent = agentRegistration.register(new AgentRegistrationRequest(
                    description, persona, relationshipType, account.accountId(), ownerUserId));
        } catch (RuntimeException e) {
            compensate(account.deviceId(), e);
            throw e;
        }

        // ── 第四步: 回绑 + 进通讯录 ─────────────────────────────────────────
        // 这两条都是本仓的写入, 不再有跨平台调用。它们失败时**不走补偿** —— 那时候聊天账号
        // 与 agent 都已经建好且互相记得, 把设备吊销掉只会把一件已经成了一半的事砸烂。
        // 抛出去之后重试是同一条路: 同一个 requestId 命中同一台设备(PAIRING 且码未过期 →
        // 原样返回), 同一个 chatAccountId 命中同一个 agent, 然后这两条再写一遍。
        pairing.attachCompanion(account.deviceId(), agent.agentId());

        String name = agent.name();
        if (name != null && !name.isBlank()) {
            // 第三步的结果里带着真名 —— 它比上面那个猜测准, 而且 SIMULATOR 账号的展示名
            // 就应该是这个 agent 的名字(见 {@code SimulatorDevice.displayName} 的说明)。
            pairing.renameAccount(account.deviceId(), name);
        }
        conversations.ensureConversation(ownerUserId, agent.agentId(), name);

        log.info("[一键创建] 用户 {} 的好友已建成: agent {} ↔ 聊天账号 {} (设备 {})",
                ownerUserId, agent.agentId(), account.accountId(), account.deviceId());
        return new ProvisionedAgentFriend(agent.agentId(), account.accountId(), agent.handle(),
                name, account.pairingCode(), account.pairingCodeExpiresAt());
    }

    /**
     * 第三步失败后的收尾 —— <b>只吊销设备, 不删账号</b>。
     *
     * <p>两个理由, 都是硬的:
     * <ul>
     *   <li>{@code simulator_devices.account_id} 是 NOT NULL 且没有外键。删掉 {@code users}
     *       行会留下一台指向不存在账号的设备(或反过来让这一行成为悬垂引用)。</li>
     *   <li><b>账号ID 一旦发出去就不可回收</b>({@code Person} 里已有这条不变量)。这次创建
     *       失败了, 但那个 id 可能已经出现在某个日志、某次响应、某个用户的屏幕上。把它删了
     *       再铸一个新号, 等于让同一个"位置"有两个历史。</li>
     * </ul>
     *
     * <p>吊销是充分的: 设备状态回 REVOKED, 配对码作废(它只在 PAIRING 状态下可被用),
     * {@code tokenVersion} 递增使任何已签发的令牌失效。那个账号因此**说不了话** ——
     * 而这正是我们欠这次失败的。重试时同一个 requestId 会命中它并复活, 于是走到底的那一次
     * 用的还是同一个账号。
     *
     * <p>补偿自己失败只记日志, 不覆盖原来的异常: 用户需要看到的是"agent 没建成"这个原因,
     * 而不是补偿过程中的第二个错误。
     */
    private void compensate(String deviceId, RuntimeException cause) {
        try {
            pairing.revokeDevice(deviceId);
            log.warn("[一键创建] agent 注册失败, 已吊销设备 {} 阻止其配对: {}", deviceId, cause.toString());
        } catch (Exception e) {
            log.error("[一键创建] 补偿失败 —— 设备 {} 仍处于可配对状态, 需人工处理", deviceId, e);
        }
    }

    /** 直传人格时能直接知道名字; 走编译链时还不知道(它要等第三步回来)。 */
    private static String personaName(PersonaJson persona) {
        if (persona == null || persona.identity() == null) return null;
        var name = persona.identity().get("name");
        return name != null && name.isTextual() && !name.asText().isBlank() ? name.asText() : null;
    }

    /**
     * 创建成功之后的回执。
     *
     * <p>六个字段, 而其中三个 {@code agentId} / {@code chatAccountId} / {@code handle}
     * 是**三个不同的东西**(agent 平台的个体 id / 聊天平台的账号 id / 人念得出来的账号ID)——
     * 这份 record 把它们并排列出来, 就是为了让读的人没有机会把任何一个当成另一个。
     *
     * <p>{@code pairingCode} 与 {@code pairingCodeExpiresAt} 可空: 重试命中一台**已经配对过**
     * 的设备时没有码可给, 那不是失败(见 {@code SimulatorPairingService#reuseExisting})。
     * 前端据此显示"已配对"而不是"配对码: (空)"。
     */
    public record ProvisionedAgentFriend(
            String agentId,
            String chatAccountId,
            String handle,
            String name,
            String pairingCode,
            LocalDateTime pairingCodeExpiresAt) {
    }
}
