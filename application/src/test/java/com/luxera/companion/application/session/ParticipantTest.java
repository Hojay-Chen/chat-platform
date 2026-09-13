package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.domain.SessionPermissionRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.application.repository.SessionPermissionRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP v2: <b>参与者模型</b> —— 这次重构里换掉整个归属链的那一节。
 *
 * <p>v1 这里是 {@code InstallationTest}: "装了吗 / 装的是哪个版本 / 卸载后再装会怎样"。
 * 那些问题在 v2 里一条都不存在了, 但**它们的继任者不是同一批问题的改名版**。安装是一份跨会话
 * 的、永久的授权; 参与者是一份只在一个会话里成立的位置。于是本类里最要紧的几条断言,
 * 讲的都是"<em>这条授权出不了这个会话</em>":
 *
 * <ul>
 *   <li>同一个人在两局里各有各的 {@code session_permission} 行 —— 在一局里的级别调不动另一局;</li>
 *   <li>同在一局里的两个人各有各的行 —— 给甲铺的授权不会顺手让乙也能写;</li>
 *   <li>人走了, 他的授权行跟着删 —— 留着就等于"一个不在场的人还能写"。</li>
 * </ul>
 *
 * <p>剩下几条是角色与容量: 自报 {@code OWNER} 会被降级、{@code OBSERVER} 拿到的是只读的一份、
 * 会话满了就进不来、{@code INVITE_ONLY} 只放主人和被邀请的人。
 *
 * <p><b>本类里没有一条断言需要区分真人和 Agent。</b> 这不是巧合, 也不是"暂时没测" ——
 * 决定 4 说的"Agent 只是另一种用户"就兑现在这里: 权限、容量、策略一律不看
 * {@code principal_type}, 所以一条为真人写的用例, 把 principal 换成 Agent 之后应当逐字成立
 * (见 {@link #anAgentGetsExactlyWhatAHumanWouldGet})。
 */
@ActiveProfiles("test")
@SpringBootTest
class ParticipantTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String CAPABILITY = "game.play";

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    ParticipantService participantService;

    @Autowired
    ApplicationSessionRepository sessions;

    @Autowired
    SessionParticipantRepository participants;

    @Autowired
    SessionPermissionRepository permissions;

    // ─────────────────────────── 角色 → 授权展开 ───────────────────────────

    /**
     * 开局的人是 {@code OWNER}, 并且立刻拿到该应用声明的<em>全部</em>能力。
     *
     * <p>为什么授权是"展开"而不是"等主人一条条批": 一个应用声明的能力就是它全部的玩法,
     * 开局的人不能玩自己的棋是没有意义的。细粒度调整留给主人事后改那一行 —— 展开是起点, 不是上限。
     */
    @Test
    void theOpenerBecomesOwnerAndGetsEveryDeclaredCapability() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, alice);

        SessionParticipantRecord me = participantService
                .find(session.getId(), PrincipalType.HUMAN, alice.principalId())
                .orElseThrow();
        assertEquals(SessionParticipantRecord.ROLE_OWNER, me.getRole());
        assertEquals(SessionParticipantRecord.STATUS_ACTIVE, me.getStatus());
        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(me));
    }

    /**
     * 展开的级别与风险上限取该能力下动作的<b>最高值</b>。
     *
     * <p>不是取平均值, 也不是取第一个: 一个能力下 {@code game.state} 要 READ、{@code game.make_move}
     * 要 EXECUTE, 授权必须按 EXECUTE 给, 否则"能看不能下"。而独立的每动作覆盖是另一回事
     * (主人事后调), 不是展开阶段的判断。
     */
    @Test
    void aMemberGetsTheHighestLevelAndRiskAmongTheCapabilitysActions() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal bob = human();
        SessionParticipantRecord member = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);

        SessionPermissionRecord grant = capabilityGrant(member);
        assertEquals("EXECUTE", grant.getPermissionLevel(), "game.make_move 要 EXECUTE");
        assertEquals("LOW", grant.getRiskCeiling(), "game.make_move 的风险是 LOW");
    }

    /** {@code OBSERVER}: 能力集合一模一样, 级别固定 READ —— 能看不能改。 */
    @Test
    void anObserverSeesTheSameCapabilitiesAtReadOnly() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        SessionParticipantRecord watcher = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_OBSERVER, true);

        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(watcher));
        assertEquals("READ", capabilityGrant(watcher).getPermissionLevel());
        assertEquals(SessionParticipantRecord.ROLE_OBSERVER, watcher.effectiveProfile());
    }

    /**
     * <b>只补不覆盖。</b> 主人把某条授权调低之后再加入一次, 调低的那个值必须留着。
     *
     * <p>这条语义是从 v1 的 {@code grantMissing} 原样继承的, 因为那条语义本来就是对的:
     * 重复加入是一件无意义的事, 它不该产生副作用。反过来说, 如果展开是"覆盖式"的, 那么一个
     * 被刻意降权的参与者只要再点一次"加入"就能把自己提回去 —— 而"加入"这个动作不该有这种权力。
     */
    @Test
    void expandingGrantsNeverOverwritesAManuallyAdjustedLevel() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal bob = human();
        SessionParticipantRecord member = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);

        SessionPermissionRecord grant = capabilityGrant(member);
        grant.setPermissionLevel("READ");           // 主人把它降成只读
        permissions.save(grant);

        participantService.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, false);

        assertEquals("READ", capabilityGrant(member).getPermissionLevel(),
                "再加入一次不该把手工调过的级别改回去");
        assertEquals(1, permissions.findByParticipantId(member.getId()).size(),
                "也不该顺手多铺一行");
    }

    // ─────────────────────────── 授权的边界就是会话 ───────────────────────────

    /**
     * <b>同一个人在两局里各有各的授权。</b> 这是 v2 与 v1 之间最本质的一处差别 ——
     * 安装是一次授权处处生效, 参与者是一局一个位置。
     *
     * <p>没有这一条, "多个 principal 共用一个应用实例"就无从谈起: 一个人这局是观察者、下局是
     * 主人, 是常态而不是异常。
     */
    @Test
    void theSamePersonGetsOneSetOfGrantsPerSession() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord first = sessionService.launch(APP_ID, alice);
        ApplicationSessionRecord second = sessionService.launch(APP_ID, alice);

        assertNotEquals(first.getId(), second.getId());

        SessionParticipantRecord inFirst = participantService
                .find(first.getId(), PrincipalType.HUMAN, alice.principalId()).orElseThrow();
        SessionParticipantRecord inSecond = participantService
                .find(second.getId(), PrincipalType.HUMAN, alice.principalId()).orElseThrow();

        assertNotEquals(inFirst.getId(), inSecond.getId(), "两局里的参与者行是两行");
        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(inFirst));
        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(inSecond));

        // 把第一局里那份降到只读 —— 第二局不受影响。这就是"v1 做不到"的那件事。
        SessionPermissionRecord downgraded = capabilityGrant(inFirst);
        downgraded.setPermissionLevel("READ");
        permissions.save(downgraded);

        assertEquals("READ", capabilityGrant(inFirst).getPermissionLevel());
        assertEquals("EXECUTE", capabilityGrant(inSecond).getPermissionLevel(),
                "一局里的调整不该漏到另一局");
    }

    /**
     * <b>给甲铺的授权不会让乙也能写。</b> 两个人同在一局, 授权行按参与者各归各的 ——
     * 这正是 {@code session_permission} 的外键从 installation 换成 participant 的意义。
     */
    @Test
    void grantsAreSiloedBetweenParticipantsOfTheSameSession() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        SessionParticipantRecord bob = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_MEMBER, true);
        SessionParticipantRecord carol = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_OBSERVER, true);

        assertNotEquals(bob.getId(), carol.getId());
        assertEquals("EXECUTE", capabilityGrant(bob).getPermissionLevel());
        assertEquals("READ", capabilityGrant(carol).getPermissionLevel());

        for (SessionPermissionRecord grant : permissions.findByParticipantId(carol.getId())) {
            assertNotEquals(bob.getId(), grant.getParticipantId());
        }
    }

    // ─────────────────────────── 加入与离开 ───────────────────────────

    /** 加入是幂等的: 第二次不新增行, 也不把 {@code joinedAt} 改掉。 */
    @Test
    void joiningTwiceIsTheSameAsJoiningOnce() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal bob = human();

        SessionParticipantRecord first = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);
        SessionParticipantRecord second = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, rowsOf(session.getId(), bob.principalId()),
                "同一个人在同一局里只有一行");
    }

    /**
     * <b>主人兑自己的 MEMBER 票不会丢所有权。</b>幂等的第二层: 已在场者重进, 角色是既定事实,
     * 不能被票上的 role 覆盖 —— R15 的生态链验收里它真发生过: 场主先兑分享链接(幂等),
     * 再想给 Agent 发定向邀请, 平台答 NOT_SESSION_OWNER, 因为第一次兑票把他降成了 MEMBER。
     * 单元测试只数了行数, 行数没变, 所有权悄悄没了。
     */
    @Test
    void anOwnerRedeemingTheirOwnMemberTicketStaysOwner() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal owner = new ResolvedPrincipal(PrincipalType.HUMAN,
                session.getOwnerPrincipalId(), null, session.getOwnerPrincipalId(),
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);

        // 场主兑一张 MEMBER 票(分享链接): 已在场的他不该被票上的角色盖掉所有权
        SessionParticipantRecord after = participantService.join(session.getId(), owner,
                SessionParticipantRecord.ROLE_MEMBER, true);

        assertEquals(SessionParticipantRecord.ROLE_OWNER, after.getRole(),
                "已在场的场主重兑 MEMBER 票, 角色不该被票覆盖");
        assertTrue(after.owner(), "所有权是既定事实, 不随兑票漂移");
    }

    /**
     * <b>自报 {@code OWNER} 会被降级成 {@code MEMBER}。</b>
     *
     * <p>挡的不是权限 —— 权限来自 profile, 而 profile 是主人能改的; 挡的是"谁说得上话"这件事的
     * 归属。{@code role=OWNER} 是唯一能调用 {@code remove} 的位置, 让它可以被自报,
     * 一个外来者就能把主人踢出去。
     */
    @Test
    void claimingToBeTheOwnerGetsYouDemoted() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        SessionParticipantRecord impostor = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_OWNER, true);

        assertEquals(SessionParticipantRecord.ROLE_MEMBER, impostor.getRole());
    }

    /** 报一个不认识的 role 也降级, 而不是原样存进库。 */
    @Test
    void anUnrecognizedRoleIsNormalizedRatherThanStored() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        SessionParticipantRecord odd = participantService.join(session.getId(), human(),
                "SUPERUSER", true);

        assertEquals(SessionParticipantRecord.ROLE_MEMBER, odd.getRole());
    }

    /**
     * <b>离开会把授权行一起删掉。</b> 留着它, 下一个人读到的是"一个已经不在场的人还能写"。
     *
     * <p>参与者那一行则<b>留着</b>(置 {@code LEFT}): 唯一键要求它留着, 否则同一个人重进会撞键;
     * 而且"他曾经在场"本身是值得查得到的事实。
     */
    @Test
    void leavingTakesTheGrantsWithItButKeepsTheRow() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal bob = human();
        SessionParticipantRecord member = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);
        assertFalse(permissions.findByParticipantId(member.getId()).isEmpty());

        participantService.leave(session.getId(), bob);

        SessionParticipantRecord after = participantService
                .find(session.getId(), PrincipalType.HUMAN, bob.principalId()).orElseThrow();
        assertEquals(SessionParticipantRecord.STATUS_LEFT, after.getStatus());
        assertTrue(after.getLeftAt() != null, "离开的时间要记下来");
        assertTrue(permissions.findByParticipantId(member.getId()).isEmpty(),
                "人走了, 授权不该还挂在那里");
    }

    /** 走出去的人能回来, 而且回到<b>同一行</b>(唯一键不允许第二行), 授权重新铺一遍。 */
    @Test
    void rejoiningReusesTheSameRowAndRebuildsTheGrants() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal bob = human();
        SessionParticipantRecord first = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);
        participantService.leave(session.getId(), bob);

        SessionParticipantRecord again = participantService.join(session.getId(), bob,
                SessionParticipantRecord.ROLE_MEMBER, true);

        assertEquals(first.getId(), again.getId());
        assertEquals(SessionParticipantRecord.STATUS_ACTIVE, again.getStatus());
        assertTrue(again.getLeftAt() == null, "回来了就不该还留着离开时间");
        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(again));
        assertEquals(1, rowsOf(session.getId(), bob.principalId()));
    }

    /** 不在场的人 {@code leave} 得到 {@code NOT_A_PARTICIPANT}, 而不是静默成功。 */
    @Test
    void leavingASessionYouWereNeverInIsRefused() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        SessionException e = assertThrows(SessionException.class,
                () -> participantService.leave(session.getId(), human()));
        assertEquals("NOT_A_PARTICIPANT", e.code());
    }

    // ─────────────────────────── 谁能进来 ───────────────────────────

    /**
     * {@code INVITE_ONLY}(开局的默认): 陌生人进不来, <b>主人和持邀请的人进得来</b>。
     *
     * <p>主人能进不是"因为它是主人所以放行"这么简单 —— 它本来就在里面。这条断言要守的是
     * 另一件事: 开局时主人那一行的写入发生在准入检查之前还是之后, 不该影响结果。
     */
    @Test
    void anInviteOnlySessionRefusesStrangersButAdmitsTheInvited() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        assertEquals(ApplicationSessionRecord.JOIN_INVITE_ONLY, session.getJoinPolicy());

        SessionException refused = assertThrows(SessionException.class,
                () -> participantService.join(session.getId(), human(),
                        SessionParticipantRecord.ROLE_MEMBER, false));
        assertEquals("SESSION_INVITE_ONLY", refused.code());

        SessionParticipantRecord invited = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_MEMBER, true);
        assertEquals(SessionParticipantRecord.STATUS_ACTIVE, invited.getStatus());
    }

    /** {@code CLOSED}: 连被邀请的也进不来 —— 这才是 {@code CLOSED} 与 {@code INVITE_ONLY} 的分别。 */
    @Test
    void aClosedSessionAdmitsNobodyNew() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        session.setJoinPolicy(ApplicationSessionRecord.JOIN_CLOSED);
        sessions.save(session);

        for (boolean viaInvitation : List.of(false, true)) {
            SessionException refused = assertThrows(SessionException.class,
                    () -> participantService.join(session.getId(), human(),
                            SessionParticipantRecord.ROLE_MEMBER, viaInvitation));
            assertEquals("SESSION_JOIN_CLOSED", refused.code());
        }
    }

    /** {@code OPEN}: 谁都能进来。 */
    @Test
    void anOpenSessionAdmitsAnyone() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        session.setJoinPolicy(ApplicationSessionRecord.JOIN_OPEN);
        sessions.save(session);

        SessionParticipantRecord walkIn = participantService.join(session.getId(), human(),
                SessionParticipantRecord.ROLE_MEMBER, false);
        assertEquals(SessionParticipantRecord.STATUS_ACTIVE, walkIn.getStatus());
    }

    /** 满了就进不来 —— 容量按 {@code ACTIVE} 人数算, 走了的人不占位。 */
    @Test
    void aFullSessionRefusesNewcomersUntilSomebodyLeaves() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(), null, null, 2);
        ResolvedPrincipal bob = human();
        ResolvedPrincipal carol = human();
        participantService.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);

        SessionException full = assertThrows(SessionException.class,
                () -> participantService.join(session.getId(), carol,
                        SessionParticipantRecord.ROLE_MEMBER, true));
        assertEquals("SESSION_FULL", full.code());

        participantService.leave(session.getId(), bob);

        SessionParticipantRecord admitted = participantService.join(session.getId(), carol,
                SessionParticipantRecord.ROLE_MEMBER, true);
        assertEquals(SessionParticipantRecord.STATUS_ACTIVE, admitted.getStatus());
    }

    /** 已经结束的会话不再收人 —— 该另开一局, 不是换个姿势重试。 */
    @Test
    void anEndedSessionAdmitsNobody() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        participantService.transition(session, ApplicationSessionRecord.STATUS_ENDED);

        SessionException e = assertThrows(SessionException.class,
                () -> participantService.join(session.getId(), human(),
                        SessionParticipantRecord.ROLE_MEMBER, true));
        assertEquals("SESSION_ENDED", e.code());
    }

    // ─────────────────────────── 状态推进 ───────────────────────────

    /**
     * 人够了就 {@code ACTIVE}。<b>默认 {@code minParticipants=1}, 所以开局那一刻就该是 ACTIVE</b> ——
     * 这条断言守着"正常情况下根本观察不到 CREATED/WAITING"这句承诺。
     */
    @Test
    void theDefaultMinimumMakesAFreshSessionImmediatelyActive() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        assertEquals(1, session.getMinParticipants());
        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE, session.getStatus());
        assertTrue(session.getStartedAt() != null, "开始了就要记下什么时候开始的");
    }

    /** 等人时停在 {@code WAITING}; 人齐了推进到 {@code ACTIVE}。 */
    @Test
    void aSessionWaitingForTheSecondPlayerAdvancesWhenTheyArrive() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(), null, 2, 8);
        assertEquals(ApplicationSessionRecord.STATUS_WAITING, session.getStatus());

        participantService.join(session.getId(), human(), SessionParticipantRecord.ROLE_MEMBER, true);

        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE,
                sessions.findById(session.getId()).orElseThrow().getStatus());
    }

    /**
     * <b>已经开始的会话不会因为有人退出而变回等待。</b>
     *
     * <p>这是想清楚之后刻意不写的那一条: 一局下到一半走了个人, 局面还在那里 —— 那不是等待,
     * 那是残局。让 {@code minParticipants} 只在开局前起作用, 是这两个状态唯一说得通的分别。
     */
    @Test
    void aRunningSessionDoesNotFallBackToWaitingWhenSomebodyLeaves() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human(), null, 2, 8);
        ResolvedPrincipal bob = human();
        participantService.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);
        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE,
                sessions.findById(session.getId()).orElseThrow().getStatus());

        participantService.leave(session.getId(), bob);

        assertEquals(ApplicationSessionRecord.STATUS_ACTIVE,
                sessions.findById(session.getId()).orElseThrow().getStatus(),
                "残局不是等待");
    }

    /** 主人能把别人移除, 被移除的人留在表里({@code REMOVED}), 于是"他曾经在场"查得到。 */
    @Test
    void theOwnerCanRemoveSomebodyAndTheRowStays() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, alice);
        ResolvedPrincipal bob = human();
        participantService.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);

        participantService.remove(session.getId(), alice, PrincipalType.HUMAN.name(),
                bob.principalId());

        SessionParticipantRecord removed = participantService
                .find(session.getId(), PrincipalType.HUMAN, bob.principalId()).orElseThrow();
        assertEquals(SessionParticipantRecord.STATUS_REMOVED, removed.getStatus());
        assertFalse(removed.active());
        assertTrue(permissions.findByParticipantId(removed.getId()).isEmpty());
    }

    /** 不是主人就移除不了别人 —— 这正是 {@code role=OWNER} 不能自报的理由。 */
    @Test
    void aMemberCannotRemoveAnybody() {
        ResolvedPrincipal alice = human();
        ApplicationSessionRecord session = sessionService.launch(APP_ID, alice);
        ResolvedPrincipal bob = human();
        ResolvedPrincipal carol = human();
        participantService.join(session.getId(), bob, SessionParticipantRecord.ROLE_MEMBER, true);
        participantService.join(session.getId(), carol, SessionParticipantRecord.ROLE_MEMBER, true);

        SessionException e = assertThrows(SessionException.class,
                () -> participantService.remove(session.getId(), bob, PrincipalType.HUMAN.name(),
                        carol.principalId()));
        assertEquals("NOT_SESSION_OWNER", e.code());
    }

    // ─────────────────────────── 没有"真人分支" ───────────────────────────

    /**
     * <b>Agent 拿到的与真人逐字相同的一份。</b>
     *
     * <p>这条断言在 v1 里写不出来 —— 那时 Agent 走的是一条单独的路({@code principal_type=AGENT}
     * 的 installation 加上专用入口)。v2 里"Agent 只是另一种用户"落在代码上就是这个样子:
     * 同一段 {@code join}, 同一批 {@code session_permission} 行, 同样的角色规则。
     */
    @Test
    void anAgentGetsExactlyWhatAHumanWouldGet() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal agent = agent();

        SessionParticipantRecord asOwner = participantService.join(session.getId(), agent,
                SessionParticipantRecord.ROLE_OWNER, true);   // 自报 OWNER: 同样被降级

        assertEquals(SessionParticipantRecord.ROLE_MEMBER, asOwner.getRole());
        assertEquals(List.of(CAPABILITY), participantService.capabilitiesOf(asOwner));
        assertEquals("EXECUTE", capabilityGrant(asOwner).getPermissionLevel());
        assertEquals(agent.companionId(), asOwner.getCompanionId(),
                "两个描述性字段照填: 事件路由要靠它们把事件送到哪个数字人");
        assertEquals(agent.userId(), asOwner.getUserId());
    }

    /**
     * {@code activeMembershipsOf} 是 {@code AgentRouteResolver} 用来回答"这个 Agent 此刻在用
     * 这个应用吗"的那把钥匙。它必须<b>只返回 ACTIVE 的</b> —— 一个已经退出这局的人不该再被唤醒。
     */
    @Test
    void activeMembershipsExcludeTheOnesWhoLeft() {
        ApplicationSessionRecord session = sessionService.launch(APP_ID, human());
        ResolvedPrincipal agent = agent();
        participantService.join(session.getId(), agent, SessionParticipantRecord.ROLE_MEMBER, true);
        assertEquals(1, participantService
                .activeMembershipsOf(PrincipalType.AGENT, agent.principalId()).size());

        participantService.leave(session.getId(), agent);

        assertTrue(participantService
                .activeMembershipsOf(PrincipalType.AGENT, agent.principalId()).isEmpty(),
                "退出之后就不该再被算作'此刻在用这个应用'");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 这一个人在这一局里的参与者行数 —— 主人那一行不算在内。 */
    private long rowsOf(String sessionId, String principalId) {
        return participantService.participantsOf(sessionId).stream()
                .filter(p -> principalId.equals(p.getPrincipalId()))
                .count();
    }

    /** 该参与者的能力级授权({@code action_id} 为空的那一行)。 */
    private SessionPermissionRecord capabilityGrant(SessionParticipantRecord participant) {
        return permissions.findByParticipantId(participant.getId()).stream()
                .filter(g -> g.getActionId() == null && CAPABILITY.equals(g.getCapabilityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "参与者应当有一条 " + CAPABILITY + " 的能力级授权"));
    }

    private static ResolvedPrincipal human() {
        String id = "p-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.HUMAN, id, null, id,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }

    private static ResolvedPrincipal agent() {
        String companionId = "dh-" + UUID.randomUUID();
        return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                "user-" + UUID.randomUUID(), null, UUID.randomUUID().toString(),
                ResolvedPrincipal.SOURCE_INTERNAL);
    }
}
