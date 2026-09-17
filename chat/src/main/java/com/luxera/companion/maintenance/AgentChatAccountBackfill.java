package com.luxera.companion.maintenance;

import com.luxera.companion.integration.HttpAgentInventoryClient;
import com.luxera.companion.integration.HttpAgentInventoryClient.AgentSummary;
import com.luxera.companion.simulator.server.SimulatorDevice;
import com.luxera.companion.simulator.server.SimulatorDeviceRepository;
import com.luxera.companion.simulator.server.SimulatorPairingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性补铸: 给**早在两个平台分家之前**就存在的 Agent 铸一个聊天账号, 并把对应关系回填仓 2。
 *
 * <h2>为什么需要它</h2>
 *
 * 账号ID 上线之前, "Agent 在聊天平台上用什么身份说话"这个问题根本没有承载它的列: 仓 2 的
 * {@code companions} 没有 {@code chat_account_id}, 仓 1 的 {@code simulator_devices}
 * 也没有。于是 51 个活着的 Agent 全部处在"有会话、有历史消息、但它自己没有账号"的状态 ——
 * 它们的历史消息里 {@code sender_id} 是空的({@code deriveSenderId} 在那时还不存在),
 * 而它们将来要连 WS 说话时, 会连一个能用的身份都拿不到。
 *
 * <p>一键创建只解决**新**Agent。已经躺在那里的那些不会自己长出账号来 —— 触发铸号的那个
 * 动作(一次创建)已经发生过了。所以要有这么一次回溯。
 *
 * <h2>方向: 从仓 1 拉, 因为它拥有两边的一半</h2>
 *
 * 铸账号用的是 {@code provisionSimulatorAccount}(本仓的), 要写的
 * {@code simulator_devices} 是本仓的表, 而"哪些还缺账号"的判据在仓 2 的
 * {@code companions.chat_account_id} 上。两边各持一半, 所以这件事必然要跨一次服务调用。
 *
 * <p>选择**本仓主动拉**而不是仓 2 推过来: 推的模式要求仓 2 知道本仓的
 * {@code /internal/companions/...} 形状, 而拉的模式只需要仓 2 回答"你有哪些 Agent"。
 * 前者把本仓的表结构知识塞进了仓 2, 后者没有 —— 两个平台之间那条线画在哪里, 就体现在
 * 这种地方。
 *
 * <h2>遍历为什么不用分页, 以及为什么失败不中断</h2>
 *
 * 一次性 runner, 输入就是全量(见仓 2 {@code CompanionRepository} 里那段说明: 补铸会改
 * {@code chat_account_id}, 于是"翻页直到空"的循环会把自己下一页的数据挤走)。失败逐条
 * 记录继续 —— 一个 Agent 推不回去不该让后面几十个都不做, 而且失败的那些下次开开关重跑即可。
 *
 * <h2>幂等: 三层, 每一层挡一种重复</h2>
 *
 * <ol>
 *   <li><b>本仓先查</b> —— {@code findFirstByCompanionIdOrderByCreatedAtAsc}。上一轮铸好了
 *       账号、绑好了设备, 但推回仓 2 那一步失败了, 于是这次它又在"缺账号"的名单里。这时
 *       正确答案是把**已有的那个账号**推回去, 而不是再铸一个。</li>
 *   <li><b>铸号带锚点</b> —— {@code provisionSimulatorAccount(name, "backfill:" + companionId)}。
 *       锚点落在 {@code simulator_devices.request_id} 的唯一索引上, 于是"上一轮铸了但没绑成"
 *       的那种半成品会被认出来并复用同一个账号。</li>
 *   <li><b>对面按值幂等</b> —— 仓 2 的 {@code attachChatAccount} 对同一个值重放返回成功,
 *       所以"推过去了但响应丢了"重跑一次不会 409。</li>
 * </ol>
 *
 * <h2>为什么默认不跑</h2>
 *
 * 它会在启动时对**全平台**的 Agent 做跨服务写入。这种东西绝不能因为一次常规重启就自己跑
 * 起来, 而且它跑完之后就没用了 —— 开关是显式的, 跑完就该关掉:
 *
 * <pre>
 *   APP_MAINTENANCE_BACKFILL_CHAT_ACCOUNTS=true   # 见 application.yml 的 app.maintenance.*
 * </pre>
 *
 * <p><b>新铸出来的设备停在 PAIRING, 不置 ACTIVE。</b>这不是漏了一步: ACTIVE 的含义是
 * "这台设备已经用一次性 secret 换过凭据", 而补铸铸出来的账号**没有任何程序持有它的 secret**
 * (配对码也没人拿走过)。把它置成 ACTIVE 就是写一个假的凭据状态; 停在 PAIRING 才是实话 ——
 * 账号在, 还没有程序来认领它。对账 runner 找的是"PAIRING 且码过期且没绑 agent"的孤儿,
 * 而这些的 {@code companion_id} 是有的, 所以不会被误判。
 */
@Slf4j
@Component
@Order(10)
@ConditionalOnProperty(name = "app.maintenance.backfill-chat-accounts", havingValue = "true")
public class AgentChatAccountBackfill implements ApplicationRunner {

    /** 幂等锚点的前缀 —— 与人经界面创建时的 UUID 区分开, 一眼能看出这行是哪条路铸的。 */
    private static final String ANCHOR_PREFIX = "backfill:";

    private final HttpAgentInventoryClient inventory;
    private final SimulatorPairingService pairing;
    private final SimulatorDeviceRepository devices;

    public AgentChatAccountBackfill(HttpAgentInventoryClient inventory,
                                    SimulatorPairingService pairing,
                                    SimulatorDeviceRepository devices) {
        this.inventory = inventory;
        this.pairing = pairing;
        this.devices = devices;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AgentSummary> missing;
        try {
            missing = inventory.list(true);
        } catch (Exception e) {
            // 拉不到名单就什么都别做 —— 这时候"名单是空的"和"对面不通"在结果上一样,
            // 而误以为"没有要补的"会让人把开关关掉, 于是这件事再也不会发生。
            log.error("[补铸] 取不到 Agent 名单, 本次不补铸(开关保持打开, 修好后重启即可): {}", e.toString());
            return;
        }
        if (missing.isEmpty()) {
            log.warn("[补铸] 仓 2 报告没有缺聊天账号的 Agent —— 无事可做。"
                    + "**请把 app.maintenance.backfill-chat-accounts 关掉**, 它不该跟着每次重启跑。");
            return;
        }

        log.warn("[补铸] 开始: 仓 2 报告 {} 个 Agent 还没有聊天账号 —— 这会为它们铸账号、"
                + "绑定设备, 并把账号 id 写回仓 2", missing.size());

        int minted = 0, reused = 0, pushed = 0, failed = 0;
        for (AgentSummary a : missing) {
            try {
                Resolution r = resolveAccount(a);
                if (r.minted()) minted++; else reused++;
                inventory.attachChatAccount(a.companionId(), r.accountId());
                pushed++;
            } catch (HttpAgentInventoryClient.AgentInventoryException e) {
                failed++;
                if (e.permanent()) {
                    // 4xx: 重跑一百次还是这个结果 —— 两边的记录对不上, 要人看。
                    log.error("[补铸] Agent {}({}) 推回仓 2 被拒({}): {} —— 这一类重跑不会好, "
                                    + "多半是那边记的账号与本仓不一致",
                            a.companionId(), a.handle(), e.status(), e.getMessage());
                } else {
                    log.warn("[补铸] Agent {}({}) 推回仓 2 失败(对面此刻不行, 下次重跑即可): {}",
                            a.companionId(), a.handle(), e.getMessage());
                }
            } catch (Exception e) {
                failed++;
                log.warn("[补铸] Agent {}({}) 补铸失败(继续下一个): {}",
                        a.companionId(), a.handle(), e.toString());
            }
        }

        log.warn("[补铸] 完成: 新铸 {} 个账号, 复用 {} 个已有账号, 回填仓 2 成功 {} 条, 失败 {} 条。"
                        + "失败的非 4xx 那些保持开关打开并重启一次即可重试(幂等)。"
                        + "**跑完请把 app.maintenance.backfill-chat-accounts 关掉。**",
                minted, reused, pushed, failed);
    }

    /** 这一次是复用还是新铸 —— 只为了最后那行日志能说清"其中多少个是新账号"。 */
    private record Resolution(String accountId, boolean minted) {}

    /**
     * 拿到这个 Agent 应该用的聊天账号 id —— 复用已有的, 或者铸一个新的。
     *
     * <p>抽成一个方法是为了让"复用 vs 新铸"这两条路在同处可读: 它们的区别只有第一行,
     * 而这两条路的**顺序**是有约束的(必须先查本仓, 再决定要不要铸) —— 反过来写就会铸出
     * 第二个账号, 而那个错误没有任何症状, 只表现为"这个 agent 有两个聊天身份"。
     */
    private Resolution resolveAccount(AgentSummary a) {
        SimulatorDevice existing = devices
                .findFirstByCompanionIdOrderByCreatedAtAsc(a.companionId())
                .orElse(null);
        if (existing != null) {
            log.info("[补铸] Agent {}({}) 本仓已有账号 {}, 只回填仓 2",
                    a.companionId(), a.handle(), existing.getAccountId());
            return new Resolution(existing.getAccountId(), false);
        }
        SimulatorPairingService.ProvisionResult pr =
                pairing.provisionSimulatorAccount(displayNameOf(a), ANCHOR_PREFIX + a.companionId());
        // 绑定失败要抛: 账号与设备已经建好了(上一个事务已提交), 但没有"这台设备属于哪个 agent"
        // 的记录, 那样下一轮就查不到它、会再铸一个。抛出去让这一条计为失败, 下一轮靠
        // request_id 锚点找回同一台设备再绑一次。
        pairing.attachCompanion(pr.deviceId(), a.companionId());
        log.info("[补铸] Agent {}({}) 铸得账号 {} + 设备 {}",
                a.companionId(), a.handle(), pr.accountId(), pr.deviceId());
        return new Resolution(pr.accountId(), true);
    }

    /**
     * 账号的展示名 —— 优先用仓 2 给的名字。
     *
     * <p>兜底用 **handle** 而不是某个固定词: 一个没有名字的 Agent 若都叫"Agent", 就又是一批
     * 分不清谁是谁的同名账号(而那正是这一整轮工作的起因)。{@code agent_xxx} 是唯一的, 而且
     * 它本来就是"这个 agent 的标识", 拿来当名字只是难看, 不会让人认错。
     */
    private static String displayNameOf(AgentSummary a) {
        if (a.name() != null && !a.name().isBlank()) return a.name();
        if (a.handle() != null && !a.handle().isBlank()) return a.handle();
        return "Agent";
    }
}
