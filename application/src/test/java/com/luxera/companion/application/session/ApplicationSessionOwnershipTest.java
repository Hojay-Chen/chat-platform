package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话的三条归属不变量, <b>每条一个反例</b>。
 *
 * <p>v1 有四条, 全部绕着安装转(会话的应用 = 安装的应用, 会话的 principal = 安装的 principal)。
 * 安装没了, 那四条里只有版本那条原样留下, 另外两条换成了参与者视角的说法:
 *
 * <pre>
 *   1. 会话的 versionId 属于该 applicationId, 且该版本行存在
 *   2. 会话行上记的 owner 真的在参与者表里有一行         (取代 v1 的反例 3 与 4)
 *   3. 会话的 ACTIVE 参与者数不超过它自己的 maxParticipants  (v1 结构上不可能出现的问题)
 * </pre>
 *
 * <p>反例是绕过 Domain Service 直接改库造出来的, 因为不变量防的正是"数据已经写歪了"这件事:
 * 校验如果在创建路径上就够用, 那它就不需要在 <em>每次使用时</em> 再跑一遍。
 *
 * <p>第 2 条只要求"有一行", <b>不要求那一行是 ACTIVE</b> —— 所以这里既有它的反例(owner 从来不是
 * 参与者), 也有它的<em>容忍例</em>({@link #anOwnerWhoLeftDoesNotBreakTheSession}): 开局的人
 * 中途退出是常态, 把"owner 已退出"判成数据损坏, 会让一局别人还在下的棋凭空变成坏数据。
 */
@ActiveProfiles("test")
@SpringBootTest
class ApplicationSessionOwnershipTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String OTHER_APP = "com.luxera.other";

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ApplicationSessionRepository sessions;

    @Autowired
    SessionParticipantRepository participants;

    @Autowired
    ApplicationVersionRepository versions;

    // ─────────────────────────── 开头: 正常路径 ───────────────────────────

    @Test
    void aFreshlyOpenedSessionSatisfiesAllThreeInvariants() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        assertEquals(APP_ID, session.getApplicationId());
        assertNotNull(session.getVersionId());
        assertNotNull(session.getOwnerPrincipalType());
        assertEquals(PrincipalType.HUMAN.name(), session.getOwnerPrincipalType());
        assertTrue(session.active());
        sessionService.verifyIntegrity(session);   // 不抛即通过
    }

    /**
     * 数字人的会话仍然同时记住 {@code companionId} 与 {@code userId} —— 事件路由要靠它们找人。
     *
     * <p>这两个字段从会话行搬到了<b>参与者行</b>上。搬得有道理: 一局里可能有多个 Agent, 谁是谁
     * 只有参与者行说得清, 而会话行只有一个格子。{@code AgentRouteResolver} 就是照这个改的。
     */
    @Test
    void anAgentParticipantKeepsBothTheCompanionAndTheUser() {
        String companionId = "dh-" + UUID.randomUUID();
        String userId = "user-" + UUID.randomUUID();
        ResolvedPrincipal agent = new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                userId, null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);

        ApplicationSessionRecord session = sessionService.launch(APP_ID, agent);
        SessionParticipantRecord me = participantService
                .find(session.getId(), PrincipalType.AGENT, companionId)
                .orElseThrow(() -> new AssertionError("Agent 应当是参与者"));

        assertEquals(companionId, me.getCompanionId());
        assertEquals(userId, me.getUserId());
        assertEquals(PrincipalType.AGENT.name(), me.getPrincipalType());
    }

    // ─────────────────────────── 反例 1: 应用不一致 ───────────────────────────

    @Test
    void aSessionOfAnotherApplicationIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.requireUsable(session.getId(), OTHER_APP));

        assertEquals("SESSION_APPLICATION_MISMATCH", e.code());
    }

    // ─────────────────────────── 反例 2: 版本不属于该应用 ───────────────────────────

    @Test
    void aSessionPointingAtAnotherApplicationsVersionIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        session.setVersionId(versionOf(OTHER_APP).getId());
        sessions.saveAndFlush(session);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_VERSION_MISMATCH", e.code());
    }

    @Test
    void aSessionPointingAtAMissingVersionIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        session.setVersionId(UUID.randomUUID().toString());
        sessions.saveAndFlush(session);

        assertEquals("SESSION_VERSION_MISMATCH",
                assertThrows(SessionException.class, () -> sessionService.verifyIntegrity(session)).code());
    }

    // ─────────────────────────── 反例 3: owner 不是参与者 ───────────────────────────

    /**
     * 会话行上记的开局人换成了另一个人 —— 这正是 v1 "反例 4" 在 v2 的形状。
     *
     * <p>v1 拦的是"会话的 principal ≠ 安装的 principal"(Agent 的会话被真人拿去用)。v2 没有安装
     * 可以对账了, 于是判据变成"这个人在不在参与者表里"。换掉 owner 而不同时补一行参与者, 会话就
     * 变成了一条<em>没人认领</em>的记录: 主人不在了, 但会话还在跑, 谁也不知道该听谁的。
     */
    @Test
    void aSessionWhoseOwnerIsNotAParticipantIsRefused() {
        String bob = principalId();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        session.setOwnerPrincipalId(bob);
        sessions.saveAndFlush(session);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_OWNER_MISMATCH", e.code());
    }

    /** 类型换了也算不是同一个人: 同一个 id 在 HUMAN 与 AGENT 名下是两个人格。 */
    @Test
    void aSessionWhoseOwnerTypeChangedIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        session.setOwnerPrincipalType(PrincipalType.AGENT.name());
        sessions.saveAndFlush(session);

        assertEquals("SESSION_OWNER_MISMATCH",
                assertThrows(SessionException.class, () -> sessionService.verifyIntegrity(session)).code());
    }

    /**
     * 第 2 条的<b>容忍例</b>: 开局的人退出了, 会话仍然自洽。
     *
     * <p>这一条与上面两条反例是一对。{@code ParticipantService.syncStatus} 刻意不因为"人走光了"
     * 就把会话判成 {@code ENDED}, 这里则确保 {@code verifyIntegrity} 不会因为 owner 那一行不是
     * ACTIVE 就报数据损坏。两处任缺一处, 结果都是: 一个人退出, 一局别人还在下的棋直接坏掉。
     */
    @Test
    void anOwnerWhoLeftDoesNotBreakTheSession() {
        String ownerId = principalId();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(ownerId));

        participantService.leave(session.getId(), human(ownerId));

        assertEquals(SessionParticipantRecord.STATUS_LEFT,
                participantService.find(session.getId(), PrincipalType.HUMAN, ownerId).orElseThrow().getStatus());
        assertTrue(sessions.findById(session.getId()).orElseThrow().active(),
                "人走了不等于会话结束了 —— 收会话有显式的 DELETE 与回收器");
        sessionService.verifyIntegrity(sessions.findById(session.getId()).orElseThrow());   // 不抛即通过
    }

    // ─────────────────────────── 反例 4: 人太多 ───────────────────────────

    /**
     * v1 结构上不可能有的问题: 一局里挤进来的人超过它自己声明的上限。
     *
     * <p>正常路径上 {@code join} 会先数人头再放行, 所以这条要绕过 join 直接写一行参与者出来 ——
     * 模拟的正是"两条加入请求同时通过了计数检查"(那是 TOCTOU, 计数与插入不在同一个原子步骤里)。
     * 使用期校验必须兜住它, 否则上限就只是一句建议。
     */
    @Test
    void aSessionWithMoreActiveParticipantsThanItsCapIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(
                APP_ID, human(principalId()), null, null, 1);

        SessionParticipantRecord gatecrasher = new SessionParticipantRecord();
        gatecrasher.setSessionId(session.getId());
        gatecrasher.setPrincipalType(PrincipalType.HUMAN.name());
        gatecrasher.setPrincipalId(principalId());
        gatecrasher.setRole(SessionParticipantRecord.ROLE_MEMBER);
        gatecrasher.setStatus(SessionParticipantRecord.STATUS_ACTIVE);
        participants.saveAndFlush(gatecrasher);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_CAPACITY_EXCEEDED", e.code());
    }

    // ─────────────────────────── 使用期校验 ───────────────────────────

    @Test
    void anUnknownSessionIsRefused() {
        assertEquals("UNKNOWN_SESSION",
                assertThrows(SessionException.class,
                        () -> sessionService.requireUsable(UUID.randomUUID().toString(), APP_ID)).code());
    }

    /** 结束时清得掉, 结束之后用不了。 */
    @Test
    void anEndedSessionIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        sessionService.end(session.getId());

        assertEquals("SESSION_ENDED",
                assertThrows(SessionException.class,
                        () -> sessionService.requireUsable(session.getId(), APP_ID)).code());
        assertFalse(sessions.findById(session.getId()).orElseThrow().active(), "状态要真的落库");
        assertEquals(1, sessionService.ofApplication(APP_ID).stream()
                        .filter(s -> s.getId().equals(session.getId())).count(),
                "结束的会话仍然查得到 —— 审计要看得见它存在过");
    }

    /** 传 null 表示"不校验应用" —— 调用方只在已经知道是哪个应用时才做这道额外检查。 */
    @Test
    void requireUsableToleratesANullApplicationId() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(principalId()));

        assertEquals(session.getId(), sessionService.requireUsable(session.getId(), null).getId());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 造一条别的应用的版本行出来, 好让"版本不属于本应用"是一条真的断言而不是"行不存在"。
     * 先查后建, 因为测试库不在用例之间回滚 —— 第二次跑这个类时那行已经在了。
     */
    private ApplicationVersionRecord versionOf(String applicationId) {
        return versions.findByApplicationIdAndVersion(applicationId, "9.9.9").orElseGet(() -> {
            ApplicationVersionRecord row = new ApplicationVersionRecord();
            row.setApplicationId(applicationId);
            row.setVersion("9.9.9");
            row.setManifestJson("{}");
            row.setManifestHash("deadbeef");
            row.setRuntimeType("NATIVE");
            return versions.saveAndFlush(row);
        });
    }

    private static String principalId() {
        return "own-" + UUID.randomUUID();
    }

    private static ResolvedPrincipal human(String principalId) {
        return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}
