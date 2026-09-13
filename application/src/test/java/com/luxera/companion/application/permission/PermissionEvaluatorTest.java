package com.luxera.companion.application.permission;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.domain.SessionPermissionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.application.repository.SessionPermissionRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限 = <b>Participant × Session permission × Capability × Action × Risk</b> —— 六条判定逐条。
 *
 * <p>v1 这个类测的是 {@code Principal × Installation grant × ...}。v2 换掉了第一个维度,
 * 于是每一条的"布景"都变了(先开一局, 而不是先装一个), 但<em>断言本身一条没删</em> —— 六步的
 * 顺序是这套权限模型唯一的文字说明, 谁把它删了, 谁就删掉了"为什么先问在场再问授权"这件事。
 *
 * <p>用真实的参与者行与授权行, 不用 mock: 这个类的全部意义就是"这几个维度凑在一起时到底放不放行",
 * 而维度之间的顺序(不在场 → 已离开 → 没授权 → 过期 → 级别不够 → 风险超上限 → 风险带)本身就是语义。
 * 把仓储换掉就等于把前两条抽走, 剩下的断言不再说明任何事。
 *
 * <p>风险带那几条用<em>构造出来的</em> {@code ActionDecl}: 内置 manifest 里没有 MEDIUM/HIGH 动作,
 * 而"中等风险要确认、高风险直接拒绝"是刻意保守的设计, 必须有测试钉住 —— 否则某天有人为了让某个
 * 动作跑通而把 HIGH 改成 LOW, 不会有任何东西阻止他。
 */
@ActiveProfiles("test")
@SpringBootTest
class PermissionEvaluatorTest {

    private static final String APP_ID = "com.luxera.tictactoe";

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    SessionParticipantRepository participants;

    @Autowired
    SessionPermissionRepository permissions;

    @Autowired
    ManifestRegistry manifests;

    // ─────────────────────────── 六条判定 ───────────────────────────

    /**
     * v1 那个"没装这个应用"的码由 {@code NOT_A_PARTICIPANT} 继任。
     *
     * <p>断言的是"码稳定", 因为客户端要靠它决定下一步: 收到这个码该去求人邀请自己, 而不是去
     * 修自己的请求体。
     */
    @Test
    void aStrangerInTheSessionIsDeniedWithAStableCode() {
        Membership someoneElse = openFresh();
        String stranger = "stranger-" + UUID.randomUUID();

        PermissionDecision decision = evaluate(someoneElse.sessionId(), stranger, action("game.make_move"));

        assertTrue(decision.denied());
        assertEquals("NOT_A_PARTICIPANT", decision.code());
    }

    /** 与上一条是两种拒绝: "不在场"该去求邀请, "在场但已离开"该重新加入。 */
    @Test
    void aParticipantWhoLeftIsDenied() {
        Membership me = openFresh();
        SessionParticipantRecord row = participantRow(me);
        row.setStatus(SessionParticipantRecord.STATUS_LEFT);
        participants.saveAndFlush(row);

        assertEquals("PARTICIPANT_INACTIVE",
                evaluate(me.sessionId(), me.principalId(), action("game.make_move")).code());
    }

    /** 在场但没授权 → NOT_AUTHORIZED; 与"不在场"是两种不同的拒绝, 客户端要能分辨。 */
    @Test
    void missingPermissionIsDenied() {
        Membership me = openFresh();
        dropPermissions(me);

        PermissionDecision decision = evaluate(me.sessionId(), me.principalId(), action("game.make_move"));
        assertEquals("NOT_AUTHORIZED", decision.code());
        assertFalse(decision.allowed());
    }

    @Test
    void expiredPermissionIsDenied() {
        Membership me = openFresh();
        SessionPermissionRecord grant = onlyPermission(me);
        grant.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        permissions.saveAndFlush(grant);

        assertEquals("GRANT_EXPIRED",
                evaluate(me.sessionId(), me.principalId(), action("game.make_move")).code());
    }

    @Test
    void permissionLevelBelowTheActionIsDenied() {
        Membership me = openFresh();
        SessionPermissionRecord grant = onlyPermission(me);
        grant.setPermissionLevel(PermissionLevel.READ.name());   // 动作要更高的级别
        permissions.saveAndFlush(grant);

        assertEquals("NOT_AUTHORIZED",
                evaluate(me.sessionId(), me.principalId(), action("game.make_move")).code());
    }

    @Test
    void riskCeilingBelowTheActionIsDenied() {
        Membership me = openFresh();
        SessionPermissionRecord grant = onlyPermission(me);
        grant.setRiskCeiling(RiskLevel.NONE.name());   // 动作是 LOW
        permissions.saveAndFlush(grant);

        assertEquals("RISK_TOO_HIGH",
                evaluate(me.sessionId(), me.principalId(), action("game.make_move")).code());
    }

    // ─────────────────────────── 放行的两种情形 ───────────────────────────

    /** 读动作只要在场就能读: 它的风险由 manifest 声明为 NONE 是被校验过的, 再要一次授权只会让 UI 莫名其妙。 */
    @Test
    void readActionNeedsOnlyParticipation() {
        Membership me = openFresh();
        dropPermissions(me);

        PermissionDecision decision = evaluate(me.sessionId(), me.principalId(), action("game.state"));
        assertTrue(decision.allowed(), "在场就能读");
        assertEquals(PermissionDecision.ALLOW_CODE, decision.code());
    }

    @Test
    void participationWithPermissionIsAllowed() {
        Membership me = openFresh();

        PermissionDecision decision = evaluate(me.sessionId(), me.principalId(), action("game.make_move"));

        assertTrue(decision.allowed());
        assertFalse(decision.confirmationRequired());
        assertEquals("ALLOW", decision.auditLabel());
        assertEquals(PermissionDecision.ALLOW_CODE, decision.code());
    }

    // ─────────────────────────── 风险带 ───────────────────────────

    @Test
    void mediumRiskRequiresConfirmationRatherThanBeingSilentlyExecuted() {
        Membership me = openFresh();
        raiseCeiling(me, RiskLevel.MEDIUM);

        PermissionDecision decision = evaluate(me.sessionId(), me.principalId(), probe(RiskLevel.MEDIUM));

        assertFalse(decision.allowed(), "允许为 false —— 但原因不是拒绝");
        assertTrue(decision.confirmationRequired());
        assertEquals("REQUIRE_CONFIRMATION", decision.auditLabel());
        assertEquals("CONFIRM_REQUIRED", decision.code());
        assertFalse(decision.denied(), "待确认不是拒绝: 确认之后动作仍然可以执行");
    }

    @Test
    void highAndCriticalRiskAreRefusedOutright() {
        Membership me = openFresh();
        raiseCeiling(me, RiskLevel.CRITICAL);   // 先放开上限, 让拒绝确实来自风险带

        for (RiskLevel risk : new RiskLevel[]{RiskLevel.HIGH, RiskLevel.CRITICAL}) {
            PermissionDecision decision = evaluate(me.sessionId(), me.principalId(), probe(risk));
            assertTrue(decision.denied(), risk + " 必须被拒绝");
            assertEquals("RISK_TOO_HIGH", decision.code());
        }
    }

    // ─────────────────────────── 身份、动作与会话缺失 ───────────────────────────

    @Test
    void missingPrincipalIsDeniedBeforeAnythingElse() {
        ApplicationManifest manifest = published();
        ApplicationManifest.ActionDecl move = manifest.action("game.make_move").orElseThrow();
        Membership me = openFresh();

        assertEquals("PRINCIPAL_REQUIRED",
                evaluator.evaluate(manifest, move, me.sessionId(), null, "someone").code());
        assertEquals("PRINCIPAL_REQUIRED",
                evaluator.evaluate(manifest, move, me.sessionId(), PrincipalType.HUMAN, "   ").code());
        // 身份缺失是第一步, 所以哪怕会话 id 也是空的, 报的仍然是身份而不是会话
        assertEquals("PRINCIPAL_REQUIRED",
                evaluator.evaluate(manifest, move, null, PrincipalType.HUMAN, null).code());
    }

    @Test
    void missingActionIsDenied() {
        ApplicationManifest manifest = published();
        assertEquals("ACTION_NOT_FOUND",
                evaluator.evaluate(manifest, null, "any-session", PrincipalType.HUMAN, "someone").code());
    }

    /**
     * 没有会话的动作不该走到执行 —— 网关的第 3 步保证会话一定解析得出来, 走到这里说明有人绕过了它。
     *
     * <p>这条同时守着幂等唯一键的那条依赖(见 {@code ActionInvocationRecord} 的类注释):
     * 唯一键里带 {@code session_id}, 而 PostgreSQL 的唯一索引对 NULL 不设防 —— 只要有一个动作能
     * 在没有会话的情况下走到执行, 那个索引就静默退化成"不约束"。所以这里必须是一个明确的拒绝,
     * 而不是"没会话就用个空串凑合"。
     */
    @Test
    void anActionWithoutASessionIsRefused() {
        ApplicationManifest manifest = published();
        ApplicationManifest.ActionDecl move = manifest.action("game.make_move").orElseThrow();

        assertEquals("SESSION_REQUIRED",
                evaluator.evaluate(manifest, move, null, PrincipalType.HUMAN, "someone").code());
        assertEquals("SESSION_REQUIRED",
                evaluator.evaluate(manifest, move, "  ", PrincipalType.HUMAN, "someone").code());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 一次"加入了某个会话的我" —— 会话 id 与被测身份绑在一起, 免得某个用例拿着 A 的会话去问 B 的权限。
     *
     * <p>每个用例开一局<em>新的</em>会话, 而不是共用一个: 开一局就是一次干净的授权展开, 用例之间
     * 不会互相污染。这也是 v2 相对 v1 的好处之一 —— 会话是廉价的, 造一个不比造一行安装贵。
     */
    private record Membership(String sessionId, String principalId) {
    }

    private PermissionDecision evaluate(String sessionId, String principalId,
                                        ApplicationManifest.ActionDecl spec) {
        return evaluator.evaluate(published(), spec, sessionId, PrincipalType.HUMAN, principalId);
    }

    /** 开一局新的, 把调用方记为 OWNER 并展开默认授权; 返回它在里面的身份。 */
    private Membership openFresh() {
        String principalId = "perm-" + UUID.randomUUID();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId));
        return new Membership(session.getId(), principalId);
    }

    private SessionParticipantRecord participantRow(Membership me) {
        return participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(
                        me.sessionId(), PrincipalType.HUMAN.name(), me.principalId())
                .orElseThrow(() -> new AssertionError("参与者应当存在: " + me.principalId()));
    }

    private SessionPermissionRecord onlyPermission(Membership me) {
        List<SessionPermissionRecord> found = permissions.findByParticipantId(participantRow(me).getId());
        assertEquals(1, found.size(), "加入会话默认给该能力一条授权");
        return found.get(0);
    }

    private void dropPermissions(Membership me) {
        permissions.deleteAll(permissions.findByParticipantId(participantRow(me).getId()));
    }

    private void raiseCeiling(Membership me, RiskLevel ceiling) {
        SessionPermissionRecord grant = onlyPermission(me);
        grant.setRiskCeiling(ceiling.name());
        permissions.saveAndFlush(grant);
    }

    private ApplicationManifest published() {
        return manifests.published(APP_ID).orElseThrow(() -> new AssertionError("内置应用应当已注册"));
    }

    private ApplicationManifest.ActionDecl action(String actionId) {
        return published().action(actionId).orElseThrow(() -> new AssertionError("manifest 里没有 " + actionId));
    }

    /** 风险带测试用的动作: 能力与内置应用一致, 于是默认授权覆盖它, 唯一变量只剩风险。 */
    private ApplicationManifest.ActionDecl probe(RiskLevel risk) {
        return new ApplicationManifest.ActionDecl("game.probe", "game.play", "探针", "风险带测试用",
                PermissionLevel.EXECUTE, risk, AttentionPolicy.AWARE, null, null);
    }

    private static ResolvedPrincipal human(String principalId) {
        return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}
