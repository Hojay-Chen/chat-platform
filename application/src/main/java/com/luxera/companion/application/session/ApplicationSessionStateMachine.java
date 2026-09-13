package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.contracts.application.PermissionLevel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * LAP v2: <b>会话的五态机</b> —— 转移表在这里, 不在调用点。
 *
 * <pre>
 *   CREATED ─┬─→ WAITING ─┬─→ ACTIVE ─┬─→ PAUSED ──→ ACTIVE
 *            │            │           │
 *            └─→ ACTIVE ──┘           └─→ ENDED ←── (以上任意状态)
 *   ENDED 是终态
 * </pre>
 *
 * <p>写成表而不是一串 {@code if}: 五态两两之间共 25 种组合, 其中合法的只有 9 种。散着写的话
 * "CREATED 能不能直接跳 PAUSED"这种问题在每一条调用路径上各有一个答案, 而其中总有一个是错的。
 *
 * <p><b>第二件职责, 也是更容易被忽略的那件: 什么状态允许什么级别的动作。</b>
 * 这不是权限 —— 权限问的是"这个人有没有资格", 这里问的是"这个会话现在接不接受"。
 * 两者都在 {@code ActionGateway} 里跑, 但拒绝的码不同({@code NOT_A_PARTICIPANT} vs
 * {@code SESSION_NOT_ACTIVE}), 调用方该做的事也不同(去找人 vs 等一会儿)。
 *
 * <p><b>只有 {@code ACTIVE} 接受写。</b> {@code WAITING} 与 {@code PAUSED} 都只放读 ——
 * "等人时看一眼棋盘"和"暂停时看看局面"都是合理的, 而让它们在等待期间偷偷落子不是。
 * {@code CREATED} 到 {@code ACTIVE} 的转换发生在参与者凑够 {@code minParticipants} 的那一刻,
 * 而默认值是 1, 所以正常情况下根本观察不到 {@code CREATED} 与 {@code WAITING}。
 */
@Component
public class ApplicationSessionStateMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            ApplicationSessionRecord.STATUS_CREATED,
            Set.of(ApplicationSessionRecord.STATUS_WAITING,
                    ApplicationSessionRecord.STATUS_ACTIVE,
                    ApplicationSessionRecord.STATUS_ENDED),
            ApplicationSessionRecord.STATUS_WAITING,
            Set.of(ApplicationSessionRecord.STATUS_ACTIVE,
                    ApplicationSessionRecord.STATUS_ENDED),
            ApplicationSessionRecord.STATUS_ACTIVE,
            Set.of(ApplicationSessionRecord.STATUS_PAUSED,
                    ApplicationSessionRecord.STATUS_ENDED),
            ApplicationSessionRecord.STATUS_PAUSED,
            Set.of(ApplicationSessionRecord.STATUS_ACTIVE,
                    ApplicationSessionRecord.STATUS_ENDED),
            ApplicationSessionRecord.STATUS_ENDED,
            Set.of());

    /** 这个状态是不是一个真实存在的状态。 */
    public boolean known(String status) {
        return status != null && ALLOWED.containsKey(status);
    }

    public boolean canTransition(String from, String to) {
        if (!known(from) || !known(to)) {
            return false;
        }
        return ALLOWED.get(from).contains(to);
    }

    /**
     * 校验一次状态转移。
     *
     * @throws SessionException {@code ILLEGAL_TRANSITION} —— 状态冲突, 不是参数错误:
     *         两个调用方同时推进同一个会话时, 输的那个应该重读, 而不是改请求。
     */
    public void require(String from, String to) {
        if (!canTransition(from, to)) {
            throw new SessionException("ILLEGAL_TRANSITION",
                    "会话状态不能从 " + from + " 变为 " + to, com.luxera.companion.contracts.application.ActionStatus.STATE_CONFLICT);
        }
    }

    /**
     * 这个状态接不接受这个级别的动作。
     *
     * <p>读永远可以(只要不是终态) —— 把读也一起挡住会让"等对手的时候看看棋盘"变成一个
     * 说不清楚的失败, 而它本来什么也没改变。
     */
    public boolean allows(String status, PermissionLevel level) {
        if (!known(status) || ApplicationSessionRecord.STATUS_ENDED.equals(status)) {
            return false;
        }
        if (level == null || level == PermissionLevel.READ) {
            return true;
        }
        return ApplicationSessionRecord.STATUS_ACTIVE.equals(status);
    }

    /** {@link #allows} 的断言形态, 供网关使用。 */
    public void requireAllows(ApplicationSessionRecord session, PermissionLevel level) {
        if (!allows(session.getStatus(), level)) {
            throw new SessionException("SESSION_NOT_ACTIVE",
                    "会话 " + session.getId() + " 处于 " + session.getStatus()
                            + ", 不接受 " + level + " 级别的动作",
                    com.luxera.companion.contracts.application.ActionStatus.STATE_CONFLICT);
        }
    }
}
