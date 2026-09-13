package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.PermissionLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话五态机: <b>哪些转移合法, 以及什么状态接受什么级别的动作</b>。
 *
 * <p>这个类里的每一条断言都是穷举出来的, 不是挑几个例子。原因是这两种错误各自都很安静:
 * 转移表少一条会让某个正常流程在没人预料的时刻 {@code ILLEGAL_TRANSITION}; 多一条则会让
 * "已经结束的会话被人重新激活"这种事故看起来一切正常。挑例子测不出来, 数组合能。
 *
 * <p><b>本类同时钉住 {方案 §11 修正} 的一条决定: {@code WAITING} 只挡写, 不挡读。</b>
 * "等人时看一眼棋盘"是合理的; 让它在等待期间失败, 会把一个语义清楚的状态变成一个没人敢用的
 * 状态。而默认 {@code minParticipants=1} 又保证正常情况下根本观察不到 {@code WAITING}。
 */
class SessionStateMachineTest {

    private final ApplicationSessionStateMachine machine = new ApplicationSessionStateMachine();

    /** 十五个状态: 五态两两组合共 25 种, 其中合法的恰好这九条。 */
    @Test
    void exactlyNineOfTheTwentyFivePairsAreLegal() {
        List<String> states = List.of(
                ApplicationSessionRecord.STATUS_CREATED,
                ApplicationSessionRecord.STATUS_WAITING,
                ApplicationSessionRecord.STATUS_ACTIVE,
                ApplicationSessionRecord.STATUS_PAUSED,
                ApplicationSessionRecord.STATUS_ENDED);

        List<String> legal = new ArrayList<>();
        for (String from : states) {
            for (String to : states) {
                if (machine.canTransition(from, to)) {
                    legal.add(from + "→" + to);
                }
            }
        }
        // 自反的一条都没有 —— "从 ACTIVE 到 ACTIVE"不是转移, 是没变。调用点靠比较字符串跳过它
        // (见 ParticipantService.transition), 所以它在这里必须被判成不合法, 而不是被默默放行。
        assertEquals(List.of(
                "CREATED→WAITING", "CREATED→ACTIVE", "CREATED→ENDED",
                "WAITING→ACTIVE", "WAITING→ENDED",
                "ACTIVE→PAUSED", "ACTIVE→ENDED",
                "PAUSED→ACTIVE", "PAUSED→ENDED"), legal);
    }

    /** 终态就是终态: 一条出边都没有, 连"ENDED→ENDED"都不算。 */
    @Test
    void endedIsTerminal() {
        for (String to : List.of(
                ApplicationSessionRecord.STATUS_CREATED,
                ApplicationSessionRecord.STATUS_WAITING,
                ApplicationSessionRecord.STATUS_ACTIVE,
                ApplicationSessionRecord.STATUS_PAUSED,
                ApplicationSessionRecord.STATUS_ENDED)) {
            assertFalse(machine.canTransition(ApplicationSessionRecord.STATUS_ENDED, to),
                    "ENDED 不该能到 " + to);
        }
    }

    /** {@code CREATED} 不能跳过开局直接暂停 —— 没开始的东西没什么可暂停的。 */
    @Test
    void createdCannotJumpStraightToPaused() {
        assertFalse(machine.canTransition(ApplicationSessionRecord.STATUS_CREATED,
                ApplicationSessionRecord.STATUS_PAUSED));
    }

    /** 认不出来的状态不是"合法状态", 两条路都必须否 —— 否则打错一个字就会静默放行。 */
    @Test
    void anUnknownStateIsNotAState() {
        assertFalse(machine.known("RUNNING"));
        assertFalse(machine.known(null));
        assertFalse(machine.canTransition(ApplicationSessionRecord.STATUS_ACTIVE, "RUNNING"));
        assertFalse(machine.canTransition("RUNNING", ApplicationSessionRecord.STATUS_ACTIVE));
    }

    /** 非法转移抛的是 {@code STATE_CONFLICT} 而不是 400: 输的那一方该重读, 不是改请求。 */
    @Test
    void anIllegalTransitionIsAStateConflictNotABadRequest() {
        SessionException e = assertThrows(SessionException.class, () ->
                machine.require(ApplicationSessionRecord.STATUS_ENDED,
                        ApplicationSessionRecord.STATUS_ACTIVE));
        assertEquals("ILLEGAL_TRANSITION", e.code());
        assertEquals(ActionStatus.STATE_CONFLICT, e.status());
    }

    /** 合法转移不抛。 */
    @Test
    void aLegalTransitionPassesQuietly() {
        machine.require(ApplicationSessionRecord.STATUS_ACTIVE,
                ApplicationSessionRecord.STATUS_PAUSED);
    }

    // ─────────────────────────── 什么状态接受什么 ───────────────────────────

    /**
     * <b>读只要不是终态就放行。</b> 等待中看一眼棋盘, 暂停时看看局面 —— 两种都什么也没改变。
     */
    @Test
    void readingIsAllowedInEveryStateExceptTheEnd() {
        for (String status : List.of(
                ApplicationSessionRecord.STATUS_CREATED,
                ApplicationSessionRecord.STATUS_WAITING,
                ApplicationSessionRecord.STATUS_ACTIVE,
                ApplicationSessionRecord.STATUS_PAUSED)) {
            assertTrue(machine.allows(status, PermissionLevel.READ), status + " 应当允许读");
        }
        assertFalse(machine.allows(ApplicationSessionRecord.STATUS_ENDED, PermissionLevel.READ),
                "结束的会话连读都不该给 —— 它的资源已经封存了");
    }

    /**
     * <b>只有 {@code ACTIVE} 接受写。</b> 这一条就是 {@code WAITING}/{@code PAUSED} 存在的全部理由:
     * 如果它们和 {@code ACTIVE} 一样放行写, 那两个状态只是看着好看。
     */
    @Test
    void onlyActiveAcceptsWrites() {
        for (PermissionLevel level : List.of(PermissionLevel.WRITE, PermissionLevel.EXECUTE)) {
            assertTrue(machine.allows(ApplicationSessionRecord.STATUS_ACTIVE, level));

            for (String status : List.of(
                    ApplicationSessionRecord.STATUS_CREATED,
                    ApplicationSessionRecord.STATUS_WAITING,
                    ApplicationSessionRecord.STATUS_PAUSED,
                    ApplicationSessionRecord.STATUS_ENDED)) {
                assertFalse(machine.allows(status, level), status + " 不该接受 " + level);
            }
        }
    }

    /** 级别为 {@code null} 时按读处理 —— 缺省不是"最严", 是"没在改东西"。 */
    @Test
    void aMissingLevelIsTreatedAsRead() {
        assertTrue(machine.allows(ApplicationSessionRecord.STATUS_WAITING, null));
    }

    /**
     * 认不出的状态不接受任何动作。<b>这一条是防漏的</b>: 库里若出现一个枚举外的新状态,
     * "不认识"必须是拒绝而不是放行。
     */
    @Test
    void anUnknownStatusAcceptsNothing() {
        assertFalse(machine.allows("RUNNING", PermissionLevel.READ));
        assertFalse(machine.allows("RUNNING", PermissionLevel.EXECUTE));
    }

    /** 网关那一侧拿到的码是 {@code SESSION_NOT_ACTIVE}, 而不是权限的 {@code NOT_A_PARTICIPANT}。 */
    @Test
    void refusingAWriteSaysSessionNotActiveRatherThanNotAParticipant() {
        ApplicationSessionRecord waiting = new ApplicationSessionRecord();
        waiting.setId("s-1");
        waiting.setStatus(ApplicationSessionRecord.STATUS_WAITING);

        SessionException e = assertThrows(SessionException.class,
                () -> machine.requireAllows(waiting, PermissionLevel.WRITE));
        assertEquals("SESSION_NOT_ACTIVE", e.code());
        assertEquals(ActionStatus.STATE_CONFLICT, e.status());

        machine.requireAllows(waiting, PermissionLevel.READ);   // 读: 不抛
    }
}
