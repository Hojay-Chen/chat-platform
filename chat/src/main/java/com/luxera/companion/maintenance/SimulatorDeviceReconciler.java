package com.luxera.companion.maintenance;

import com.luxera.companion.integration.HttpAgentInventoryClient;
import com.luxera.companion.integration.HttpAgentInventoryClient.AgentSummary;
import com.luxera.companion.simulator.server.SimulatorDevice;
import com.luxera.companion.simulator.server.SimulatorDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账: 两个平台各自记了一半的"这个 Agent 用哪个聊天账号说话", 把它们对一遍 —— <b>只报不改</b>。
 *
 * <h2>为什么必须有这么一个东西, 而不是"应该不会出错"</h2>
 *
 * 需求 ④ 那条链是<b>跨两个平台、跨三个事务</b>的: 仓 1 铸账号 + 铸设备(一个事务)、调仓 2
 * 的 openAPI 建 agent(第二个平台的第二个事务)、回写设备绑定 + 建会话(又一个事务)。任何一步
 * 之后进程死掉、对面超时、补偿本身再失败, 都会留下一半的记录。
 *
 * <p>而这一半的记录<b>不会报错</b>。它只表现为两件在界面上毫无区别的事: 一个 Agent
 * 永远不说话(它其实没有能连上来的设备), 或者一个聊天账号永远没人认领(它背后没有 agent)。
 * 用户看到的是"它怎么不理我", 而不是任何一条错误日志 —— 所以这件事必须由一次主动比对
 * 发现, 不能等它自己浮出来。
 *
 * <h2>两个方向, 两个问题, 都很具体</h2>
 *
 * <ol>
 *   <li><b>账号铸了, agent 没建成</b>(本仓自己就能看见): {@code status=PAIRING} 且
 *       {@code companion_id IS NULL} 且配对码已过期超过宽限期。正常流程里这个状态只存在于
 *       一次请求的内部(第二步与第三步之间), 请求结束时就该被消掉 —— 要么绑上了 agent,
 *       要么在第三步失败时被补偿置成 REVOKED。所以它还留在这里, 说明那次请求<b>没有正常结束</b>。</li>
 *   <li><b>agent 在, 但永远说不了话</b>(要问仓 2 才知道): 仓 2 记着 {@code chat_account_id},
 *       而本仓这台设备上查不到任何对应行。agent 有身份、有会话、有历史, 但它没有任何凭据
 *       可以连上 WS —— 它是个哑巴。</li>
 * </ol>
 *
 * <h2>判据只用两个权威列: {@code status} 与 {@code companion_id}</h2>
 *
 * <p><b>刻意不看 {@code updated_at}</b>。那一列是 Hibernate 的 {@code @UpdateTimestamp},
 * 它回答的是"这行最后一次被写过是什么时候" —— 而"最后一次被写"既可能是那次失败的回滚,
 * 也可能是一次无关紧要的 {@code last_seen_at} 刷新(设备每连一次 WS 都会写)。拿它当判据,
 * 一条正在飞的请求会被误判成孤儿, 而一条真正卡死的记录会因为某次心跳而看起来没事。
 *
 * <h2>判据与报告分开, 是因为判据要被测</h2>
 *
 * <p>{@link #scan} 与 {@link #voiceGaps} 是纯函数(输入两张列表, 输出分类), 报告方法只负责
 * 把结果写成日志。这样"什么算孤儿"这条规则可以直接被单测钉住 —— 如果它埋在日志语句里,
 * 唯一能测它的方式就是去解析日志文本, 而那种测试会在任何人调整措辞时变红, 于是很快就
 * 没人看它了。
 *
 * <h2>只报不改 —— 这是刻意的, 不是没做完</h2>
 *
 * <p>两类的"改"都很诱人, 而且都不该由这个 runner 做:
 *
 * <ul>
 *   <li>方向 1 里"agent 其实建成了、只是没绑上"的那些, 修起来确实只要一句本地
 *       {@code attachCompanion}。但它<b>不该在启动时自动发生</b>: 一个启动钩子按启发式
 *       规则写归属关系, 而写错了没有任何人会看到 —— 归属写错意味着一个 Agent 从此用别人的
 *       身份说话。报告里给出 {@code agentId}, 一行命令就能修, 由人下这一手。</li>
 *   <li>方向 1 里"agent 压根没建成"的那些<b>没法自动修</b>: 重试需要原始 persona, 而它随
 *       那次失败的请求一起没了(它从来没落过库)。所以这一类只能报出来 —— 这也正是数据必须
 *       带上账号 id 的原因: 人至少能去仓 2 搜一下这个账号有没有被登记。</li>
 * </ul>
 *
 * <p>只报还有一个好处: 它可以被反复跑。带写入的对账跑第二次时输入已经被自己改过了,
 * 于是没有人能说清第二份报告意味着什么。
 *
 * <h2>为什么默认不跑</h2>
 *
 * 它本身不写任何东西, 但它会<b>跨服务调用仓 2</b>, 且在两边不一致时刷出成片的 ERROR ——
 * 那是一个"该有人看一眼"的信号, 不该混在每次常规重启的输出里被忽略掉。开关是显式的:
 *
 * <pre>
 *   APP_MAINTENANCE_RECONCILE_DEVICES=true     # 见 application.yml 的 app.maintenance.*
 * </pre>
 *
 * <p>跑到什么算干净, 由报告最后那行给出; 它不会"通过"或"失败" —— 没有退出码可以表达
 * "43 个里 2 个对不上", 那是给人读的。
 */
@Slf4j
@Component
@Order(30)
@ConditionalOnProperty(name = "app.maintenance.reconcile-devices", havingValue = "true")
public class SimulatorDeviceReconciler implements ApplicationRunner {

    /** 本仓设备的状态值。{@code PAIRING} 的意思见 {@link #scan}: 它是唯一一个"等人的"状态。 */
    static final String STATUS_PAIRING = "PAIRING";

    /**
     * 已经消掉的状态。它值得单独一档 —— 见 {@link #voiceGaps} 的三分类。
     */
    static final String STATUS_REVOKED = "REVOKED";

    private final SimulatorDeviceRepository devices;
    private final HttpAgentInventoryClient inventory;

    /**
     * 宽限期: 配对码过期之后还要再等多久, 才允许把一条 PAIRING 记录判成"没有正常结束"。
     *
     * <p>它必须大于<b>一次一键创建的最坏耗时</b>(仓 1 本地写入 + 一次跨服务 openAPI 调用),
     * 否则一条正在飞的请求会被判成孤儿。默认 15 分钟比 8092 那 5 秒的超时大两个数量级 ——
     * 这个余量是刻意留大的: 误报一条"孤儿"的代价是人去查一个正常的记录, 而它换来的是
     * "绝不误报"这个可以完全信任的性质。
     */
    private final Duration grace;

    public SimulatorDeviceReconciler(
            SimulatorDeviceRepository devices,
            HttpAgentInventoryClient inventory,
            @Value("${app.maintenance.reconcile-grace-minutes:15}") long graceMinutes) {
        this.devices = devices;
        this.inventory = inventory;
        this.grace = Duration.ofMinutes(graceMinutes);
    }

    @Override
    public void run(ApplicationArguments args) {
        // 一次全量读, 两个方向都用它。这张表每一行都是"某个 agent 的一个聊天身份",
        // 所以它的上界就是 agent 数量(几十到几百行), 不值得为每个 agent 各查一次。
        List<SimulatorDevice> all = devices.findAll();
        LocalDateTime now = LocalDateTime.now();

        // 仓 2 的名单也只取一次。两个方向对"取不到"的处置不一样(方向 1 退化成"未确认"照常
        // 报告, 方向 2 只能整个跳过), 所以把失败留给各个方向自己判断 —— 但调用只有这一次。
        List<AgentSummary> agents = null;
        try {
            agents = inventory.list(false);
        } catch (Exception e) {
            log.warn("[对账] 取不到仓 2 的 Agent 名单: {} —— 方向 1 的分类退化为'未确认', "
                    + "方向 2(它本来就只能靠这份名单)整段跳过。", e.toString());
        }

        Scan scan = scan(all, now, grace);
        int unfinished = reportUnfinishedCreations(scan, agents);
        ReverseReport reverse = reportAgentsWithoutVoice(all, agents);

        log.warn("[对账] 完成: 方向1(账号铸了/agent 没建成) {} 条, 方向2(agent 在/没有能用的设备) "
                        + "{} 条, 仓 2 报缺聊天账号 {} 个。{}",
                unfinished, reverse.broken(), reverse.missingAccounts(),
                unfinished + reverse.broken() == 0
                        ? "**两个方向都对得上。**"
                        : "**上面每一条都需要人看一眼** —— 本 runner 只报不改, "
                          + "理由见类注释(自动改写归属关系的代价比这条日志大)。");
    }

    // ── 方向 1 的判据 ────────────────────────────────────────────────────────

    /**
     * 一次扫描的结果。分成两张表不是为了好看: {@code noExpiry} 那些<b>判据根本够不到</b>
     * (没有过期时间就没有"已过期"可言), 把它们混进孤儿里是编造, 丢掉又会让一个永久
     * 看不见的洞一直存在 —— 所以它们是第三类, 单独报。
     */
    record Scan(List<SimulatorDevice> orphans, List<SimulatorDevice> noExpiry) {}

    /**
     * 找"没有正常结束"的一键创建。
     *
     * <p>两个条件缺一不可, 每一个都在排除一类正常情况:
     *
     * <ul>
     *   <li>{@code status=PAIRING} —— ACTIVE 是"已经用 secret 换过凭据", REVOKED 是"已经
     *       被消掉了"。只有 PAIRING 是"等着人来认领"。</li>
     *   <li>{@code companion_id IS NULL} —— 一键创建第四步会把它写上。补铸铸出来的设备
     *       也停在 PAIRING, 但它们的 {@code companion_id} 是有值的, 所以不算孤儿: 那些
     *       账号确实还没有程序来认领, 但那是有意为之的(见 {@code AgentChatAccountBackfill}
     *       类注释最后一段)。</li>
     * </ul>
     *
     * <p>第三个条件是"码已过期超过宽限期", 见 {@link #grace}。
     */
    static Scan scan(List<SimulatorDevice> all, LocalDateTime now, Duration grace) {
        List<SimulatorDevice> orphans = new ArrayList<>();
        List<SimulatorDevice> noExpiry = new ArrayList<>();
        for (SimulatorDevice d : all) {
            if (!STATUS_PAIRING.equals(d.getStatus()) || d.getCompanionId() != null) continue;
            LocalDateTime expiresAt = d.getPairingCodeExpiresAt();
            if (expiresAt == null) {
                // 码是有 TTL 的, 铸的时候一定写了这一列 —— 所以没有它的 PAIRING 行是个
                // 形状不对的记录。它不是孤儿(不满足"已过期"), 但它也不该被静默跳过。
                noExpiry.add(d);
                continue;
            }
            if (expiresAt.isAfter(now.minus(grace))) continue;
            orphans.add(d);
        }
        return new Scan(orphans, noExpiry);
    }

    // ── 方向 2 的判据 ────────────────────────────────────────────────────────

    /**
     * 方向 2 的三分类。
     *
     * <p>{@code silent} = 本仓一台设备都查不到; {@code revoked} = 有设备但全是 REVOKED;
     * {@code missingAccount} = 仓 2 那边压根还没有聊天账号(归补铸 runner 管, 这里只报数)。
     */
    record VoiceGap(List<AgentSummary> silent, List<AgentSummary> revoked, int missingAccount) {

        int broken() {
            return silent.size() + revoked.size();
        }
    }

    /**
     * 逐个确认"仓 2 说它有的那个聊天账号, 本仓有没有能让它说话的设备"。
     *
     * <p>分三档, 而不是"有 device 就对、没有就错":
     *
     * <ol>
     *   <li><b>有非 REVOKED 的设备</b> —— 正常。REVOKED 之外的任何一个状态都算: PAIRING 的
     *       意思是"还没人来认领", 那是一个合法的中间态(补铸铸出来的账号就停在这里,
     *       而它确实是可以被认领的)。</li>
     *   <li><b>有设备但全是 REVOKED</b> —— 不是正常。<b>只查"存不存在"会把这一档报成健康</b>,
     *       而它恰恰是最坏的一种: 账号在、会话在、agent 在, 但凭据已经被消掉, 谁也连不上来,
     *       而且没有任何地方会因此报错。区分出来是因为处置不同 —— 这一档要重发凭据,
     *       下面那一档要建账号。</li>
     *   <li><b>完全查不到设备</b> —— 账号是从别的路写进仓 2 的(比如第三方的 openAPI),
     *       而那条路建不出"能说话的 agent"。</li>
     * </ol>
     *
     * <p>账号同一个行可能有<b>多台</b>设备({@code account_id} 上没有唯一约束), 而只要其中
     * 有一台不是 REVOKED 这个 agent 就能说话 —— 所以判据是"全部 REVOKED"而不是"有一台
     * REVOKED"。
     */
    static VoiceGap voiceGaps(List<AgentSummary> agents, List<SimulatorDevice> all) {
        Map<String, List<SimulatorDevice>> byAccount = new HashMap<>();
        for (SimulatorDevice d : all) {
            byAccount.computeIfAbsent(d.getAccountId(), k -> new ArrayList<>()).add(d);
        }

        List<AgentSummary> silent = new ArrayList<>();
        List<AgentSummary> revoked = new ArrayList<>();
        int missingAccount = 0;
        for (AgentSummary a : agents) {
            if (a.chatAccountId() == null || a.chatAccountId().isBlank()) {
                missingAccount++;
                continue;
            }
            List<SimulatorDevice> mine = byAccount.getOrDefault(a.chatAccountId(), List.of());
            if (mine.isEmpty()) {
                silent.add(a);
            } else if (mine.stream().allMatch(d -> STATUS_REVOKED.equals(d.getStatus()))) {
                revoked.add(a);
            }
        }
        return new VoiceGap(silent, revoked, missingAccount);
    }

    // ── 报告(只写日志) ──────────────────────────────────────────────────────

    /**
     * 把方向 1 的扫描结果写成人能读的报告。
     *
     * @param agents 仓 2 的 Agent 名单; {@code null} = 这次没取到, 分类退化为"未确认"
     *               (方向 1 不需要仓 2 也能跑, 所以取不到不是不做的理由)
     * @return 判为孤儿的条数(只为最后那行汇总)
     */
    private int reportUnfinishedCreations(Scan scan, List<AgentSummary> agents) {
        // "这个账号在仓 2 有没有被登记"—— 方向 1 的两种孤儿修法完全不同, 而这个区别
        // 就落在这一个查表上。
        Map<String, String> agentOfAccount = new HashMap<>();
        boolean repo2Known = agents != null;
        for (AgentSummary a : repo2Known ? agents : List.<AgentSummary>of()) {
            if (a.chatAccountId() != null && !a.chatAccountId().isBlank()) {
                agentOfAccount.put(a.chatAccountId(), a.companionId());
            }
        }

        for (SimulatorDevice d : scan.noExpiry()) {
            log.warn("[对账] 设备 {} 状态 PAIRING 却没有过期时间, 判据用不上它, 请人工确认: 账号 {}",
                    d.getDeviceId(), d.getAccountId());
        }

        List<SimulatorDevice> orphans = scan.orphans();
        if (orphans.isEmpty()) return 0;

        log.error("[对账] 方向 1: {} 条一键创建没有正常结束 —— 账号已铸, agent 未绑定。"
                        + "正常流程里它们要么被绑上 agent、要么在失败时被补偿置成 REVOKED, "
                        + "所以留在这里说明那次请求中途断了。",
                orphans.size());
        for (SimulatorDevice d : orphans) {
            String agentId = repo2Known ? agentOfAccount.get(d.getAccountId()) : null;
            if (agentId != null) {
                // 这个才是"只差一步"的那种: agent 建成了, 第四步的回写没做成。
                log.error("[对账]   · 设备 {} 账号 {} —— 仓 2 的 agent {} 已经建好, 只差把设备绑上去"
                                + "(本 runner 不代劳: 归属关系由启发式规则写进去而写错了没人看得见)。"
                                + "配对码已于 {} 过期, 要让它连上需要重新铸码。",
                        d.getDeviceId(), d.getAccountId(), agentId, d.getPairingCodeExpiresAt());
            } else {
                log.error("[对账]   · 设备 {} 账号 {} —— {}。账号 id 一旦发出就不可回收"
                                + "(见 Person 里那条不变量), 所以别删这个账号; 重试建 agent 需要"
                                + "原始 persona, 而它随那次失败的请求一起没了, 只能由人决定这个账号怎么办。",
                        d.getDeviceId(), d.getAccountId(),
                        repo2Known ? "仓 2 里查不到用这个账号登记的 agent"
                                : "仓 2 名单这次没取到, 未确认");
            }
        }
        return orphans.size();
    }

    /** 把方向 2 的分类写成人能读的报告。 */
    private ReverseReport reportAgentsWithoutVoice(List<SimulatorDevice> all,
                                                   List<AgentSummary> agents) {
        if (agents == null) {
            // "名单是空的"和"对面不通"在结果上一样, 而把后者当成前者会得出"全部对得上"这个
            // 完全相反的结论 —— 所以这里必须什么都不说, 而不是说"没问题"。
            log.error("[对账] 方向 2 跳过: 这次没取到仓 2 的 Agent 名单, 无法判断它们能不能说话。"
                    + "**不要把这次的空结果读成'全对得上'。**");
            return new ReverseReport(0, 0);
        }

        VoiceGap gap = voiceGaps(agents, all);

        if (!gap.revoked().isEmpty()) {
            log.error("[对账] 方向 2: {} 个 Agent 的聊天设备**全部已吊销** —— 账号在、agent 在, "
                            + "但没有任何凭据能连上来, 而这件事不会在任何地方报错。要重发凭据。",
                    gap.revoked().size());
            for (AgentSummary a : gap.revoked()) {
                log.error("[对账]   · agent {} ({}) 账号 {}",
                        a.companionId(), a.handle(), a.chatAccountId());
            }
        }
        if (!gap.silent().isEmpty()) {
            log.error("[对账] 方向 2: {} 个 Agent 在仓 2 记着聊天账号, 本仓却查不到任何设备 —— "
                            + "它们是哑巴: 有身份、有会话, 但连不上来。这些账号多半是从 openAPI "
                            + "那条路写过去的, 而那条路建不出'能说话的 agent'。",
                    gap.silent().size());
            for (AgentSummary a : gap.silent()) {
                log.error("[对账]   · agent {} ({}) 账号 {}",
                        a.companionId(), a.handle(), a.chatAccountId());
            }
        }
        return new ReverseReport(gap.broken(), gap.missingAccount());
    }

    /** 方向 2 的结果 —— 只为最后那行汇总。 */
    private record ReverseReport(int broken, int missingAccounts) {}
}
