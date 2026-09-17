package com.luxera.companion.maintenance;

import com.luxera.companion.integration.HttpAgentInventoryClient.AgentSummary;
import com.luxera.companion.maintenance.SimulatorDeviceReconciler.Scan;
import com.luxera.companion.maintenance.SimulatorDeviceReconciler.VoiceGap;
import com.luxera.companion.simulator.server.SimulatorDevice;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账的**判据** —— 报告的措辞会变, 这些不行。
 *
 * <p>这里钉的都是"错一个方向就静默失效"的性质: 判宽了会把正在飞的请求报成孤儿(于是没人
 * 再信这份报告), 判窄了会把真正的孤儿漏掉(于是这份报告看起来永远干净)。两种都不可接受,
 * 所以每一条排除规则都有一个用例。
 */
class SimulatorDeviceReconcilerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 22, 0);
    private static final Duration GRACE = Duration.ofMinutes(15);

    // ── 方向 1: 账号铸了, agent 没建成 ──────────────────────────────────────

    /**
     * 正例: PAIRING + 没绑 agent + 码过期超过宽限期。
     *
     * <p>这条记录的来历是一次中途断掉的请求 —— 正常流程结束时它要么绑上了 agent,
     * 要么被补偿置成 REVOKED。
     */
    @Test
    void anExpiredUnboundPairingRowIsAnOrphan() {
        SimulatorDevice d = device("dev-1", "acc-1", "PAIRING", null, NOW.minusHours(2));

        Scan scan = SimulatorDeviceReconciler.scan(List.of(d), NOW, GRACE);

        assertEquals(List.of(d), scan.orphans());
        assertTrue(scan.noExpiry().isEmpty());
    }

    /**
     * 刚铸出来的那条不算 —— 它此刻<b>正在</b>一次请求的内部。
     *
     * <p>这是"绝不误报"那条性质的用例: 少了宽限期这道闸, 每一次一键创建都会在日志里
     * 留下一条 ERROR。
     */
    @Test
    void anUnboundRowWhoseCodeHasNotExpiredYetIsInFlightNotAnOrphan() {
        SimulatorDevice fresh = device("dev-1", "acc-1", "PAIRING", null, NOW.plusMinutes(5));
        SimulatorDevice justExpired = device("dev-2", "acc-2", "PAIRING", null, NOW.minusMinutes(1));

        Scan scan = SimulatorDeviceReconciler.scan(List.of(fresh, justExpired), NOW, GRACE);

        assertTrue(scan.orphans().isEmpty(), "码没过期、以及过期还在宽限期内, 都不算孤儿");
    }

    /** 宽限期一过就报 —— 上一条的边界在另一侧。 */
    @Test
    void anUnboundRowPastTheGracePeriodIsReported() {
        SimulatorDevice d = device("dev-1", "acc-1", "PAIRING", null, NOW.minus(GRACE).minusSeconds(1));

        assertEquals(List.of(d), SimulatorDeviceReconciler.scan(List.of(d), NOW, GRACE).orphans());
    }

    /**
     * 补铸铸出来的那些不是孤儿 —— 它们的 {@code companion_id} 是有值的。
     *
     * <p>这条最值得钉住: 它们<b>确实</b>停在 PAIRING 且没人认领, 所以"PAIRING 且没被认领"
     * 这个更宽松的判据会把 51 条正常记录全部报成孤儿, 那份报告当场就废了。
     */
    @Test
    void aBackfilledRowIsNotAnOrphanEvenThoughItSitsInPairing() {
        SimulatorDevice backfilled = device("dev-1", "acc-1", "PAIRING", "agent-1", NOW.minusDays(3));

        Scan scan = SimulatorDeviceReconciler.scan(List.of(backfilled), NOW, GRACE);

        assertTrue(scan.orphans().isEmpty());
        assertTrue(scan.noExpiry().isEmpty());
    }

    /** ACTIVE / REVOKED 都不看 —— 它们不是"等着人来认领"的状态。 */
    @Test
    void onlyPairingRowsAreConsidered() {
        List<SimulatorDevice> others = List.of(
                device("dev-1", "acc-1", "ACTIVE", null, NOW.minusDays(1)),
                device("dev-2", "acc-2", "REVOKED", null, NOW.minusDays(1)));

        assertTrue(SimulatorDeviceReconciler.scan(others, NOW, GRACE).orphans().isEmpty());
    }

    /**
     * 没有过期时间的 PAIRING 行单独报 —— <b>不是孤儿, 也不是"没事"</b>。
     *
     * <p>码是有 TTL 的, 铸的时候一定写了这一列, 所以这种行形状不对。把它混进孤儿里是编造
     * (没有任何"已过期"可言), 静默丢掉则是一个永久看不见的洞。
     */
    @Test
    void aPairingRowWithoutAnExpiryIsReportedSeparatelyRatherThanSkipped() {
        SimulatorDevice malformed = device("dev-1", "acc-1", "PAIRING", null, null);

        Scan scan = SimulatorDeviceReconciler.scan(List.of(malformed), NOW, GRACE);

        assertTrue(scan.orphans().isEmpty());
        assertEquals(List.of(malformed), scan.noExpiry());
    }

    // ── 方向 2: agent 在, 但永远说不了话 ────────────────────────────────────

    @Test
    void anAgentWhoseAccountHasNoDeviceCanNeverSpeak() {
        VoiceGap gap = SimulatorDeviceReconciler.voiceGaps(
                List.of(agent("agent-1", "acc-1")), List.of());

        assertEquals(1, gap.silent().size());
        assertTrue(gap.revoked().isEmpty());
        assertEquals(1, gap.broken());
    }

    /**
     * 有一台能用的设备就是好的 —— <b>哪怕同账号下还躺着别的行</b>。
     *
     * <p>{@code account_id} 上没有唯一约束, 所以"这个账号有几台设备"不是本类能假设的事。
     * 判据因此必须是"全部 REVOKED", 而不是"有一台 REVOKED"。
     */
    @Test
    void anyUsableDeviceMakesTheAgentSpeakable() {
        VoiceGap gap = SimulatorDeviceReconciler.voiceGaps(
                List.of(agent("agent-1", "acc-1")),
                List.of(device("dev-1", "acc-1", "REVOKED", "agent-1", NOW.minusDays(2)),
                        device("dev-2", "acc-1", "PAIRING", "agent-1", NOW.minusDays(1))));

        assertTrue(gap.silent().isEmpty());
        assertTrue(gap.revoked().isEmpty());
        assertEquals(0, gap.broken());
    }

    /**
     * 全是 REVOKED 的那一档必须被单独看出来 —— 这是<b>只查"存不存在"会漏掉</b>的那一类。
     *
     * <p>账号在、agent 在、会话在, 但凭据被消掉了, 谁也连不上来, 而且不会报错。
     * 它的修法(重发凭据)与"没有设备"那一档(建账号)完全不同, 所以不能合并。
     */
    @Test
    void anAgentWhoseDevicesAreAllRevokedIsReportedAsSuchNotAsHealthy() {
        VoiceGap gap = SimulatorDeviceReconciler.voiceGaps(
                List.of(agent("agent-1", "acc-1")),
                List.of(device("dev-1", "acc-1", "REVOKED", "agent-1", NOW.minusDays(2)),
                        device("dev-2", "acc-1", "REVOKED", "agent-1", NOW.minusDays(1))));

        assertTrue(gap.silent().isEmpty());
        assertEquals(List.of("agent-1"), gap.revoked().stream().map(AgentSummary::companionId).toList());
        assertEquals(1, gap.broken(), "已吊销也算'说不了话', 要计进 broken");
    }

    /** 账号都是别的账号的 —— 一台设备都不该被认领。 */
    @Test
    void devicesBelongingToOtherAccountsDoNotCount() {
        VoiceGap gap = SimulatorDeviceReconciler.voiceGaps(
                List.of(agent("agent-1", "acc-1")),
                List.of(device("dev-1", "acc-2", "ACTIVE", "agent-2", NOW.minusDays(1))));

        assertEquals(1, gap.silent().size());
    }

    /**
     * 还没有聊天账号的 agent 归补铸 runner 管 —— 这里只报数, 不算"说不了话"。
     *
     * <p>混进来的话, 补铸跑之前这份对账报告会永远红着 98 条, 而它们要的处置
     * (铸账号)在这条链的另一个 runner 上。两份报告各说各的事。
     */
    @Test
    void agentsWithoutAChatAccountYetAreCountedSeparatelyNotAsBroken() {
        VoiceGap gap = SimulatorDeviceReconciler.voiceGaps(
                List.of(agent("agent-1", null), agent("agent-2", "  ")), List.of());

        assertTrue(gap.silent().isEmpty());
        assertTrue(gap.revoked().isEmpty());
        assertEquals(2, gap.missingAccount());
        assertEquals(0, gap.broken());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static SimulatorDevice device(String deviceId, String accountId, String status,
                                         String companionId, LocalDateTime expiresAt) {
        SimulatorDevice d = new SimulatorDevice();
        d.setDeviceId(deviceId);
        d.setAccountId(accountId);
        d.setStatus(status);
        d.setCompanionId(companionId);
        d.setPairingCodeExpiresAt(expiresAt);
        d.setScopes("conversation.list");
        return d;
    }

    private static AgentSummary agent(String companionId, String chatAccountId) {
        return new AgentSummary(companionId, "晚晚", chatAccountId, "agent_" + companionId);
    }
}
