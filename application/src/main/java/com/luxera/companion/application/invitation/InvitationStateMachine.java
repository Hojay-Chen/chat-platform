package com.luxera.companion.application.invitation;

import com.luxera.companion.application.domain.SessionInvitationRecord;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * LAP v2: <b>邀请的四态机</b> —— 转移表在这里, 不在调用点。
 *
 * <pre>
 *   CREATED ──┬── 消费 ──→ CONSUMED
 *             ├── 过期 ──→ EXPIRED
 *             └── 撤销 ──→ REVOKED
 *
 *   三个终态各归各: 一张票只有一种死法, 而且死法不可逆转。
 * </pre>
 *
 * <p>{@code CONSUMED} / {@code EXPIRED} / {@code REVOKED} 是<em>三个</em>终态而不是一个:
 * 调用方要回答的问题不同 —— "这张票为什么不能用了"决定了他下一步该做什么(再来一次用掉下一张
 * / 等一张新的 / 去问主人为什么收回)。§15 的图里恰好是它们三个, 照抄实现。
 *
 * <p>与 {@code ApplicationSessionStateMachine} 同一套表驱动写法, 但不是同一个东西: 会话机还有
 * 第二件职责("什么状态接受什么级别的动作"), 邀请机没有 —— 一张票只有"能不能用"一种问题, 而那
 * 个问题由 {@link SessionInvitationRecord#usable} 回答(<b>不算状态机</b>), 状态机只负责
 * <em>终局</em>的那一推, 且终局推出去就不回头。
 */
@Component
public class InvitationStateMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            SessionInvitationRecord.STATUS_CREATED,
            Set.of(SessionInvitationRecord.STATUS_CONSUMED,
                    SessionInvitationRecord.STATUS_EXPIRED,
                    SessionInvitationRecord.STATUS_REVOKED),
            SessionInvitationRecord.STATUS_CONSUMED, Set.of(),
            SessionInvitationRecord.STATUS_EXPIRED, Set.of(),
            SessionInvitationRecord.STATUS_REVOKED, Set.of());

    public boolean known(String status) {
        return status != null && ALLOWED.containsKey(status);
    }

    public boolean canTransition(String from, String to) {
        if (!known(from) || !known(to)) {
            return false;
        }
        return ALLOWED.get(from).contains(to);
    }

    /** 校验一次转移。终态出去不合法 —— 用掉的票不能再用, 撤回的票不能撤回。 */
    public void require(String from, String to) {
        if (!canTransition(from, to)) {
            throw new SessionException("ILLEGAL_INVITATION_TRANSITION",
                    "邀请状态不能从 " + from + " 变为 " + to, ActionStatus.STATE_CONFLICT);
        }
    }

    /**
     * 推进一张票并落库 —— 幂等: 已经是目标态的票原样返回, 不重复 save。
     *
     * <p>幂等是刻意的: "过期"这一推可能同时从两条路上来(校验时发现到点了、以及定时清扫),
     * 两条路撞上同一张票时, 其中一个该是赢家, 另一个是无操作 —— 而不是一个 {@code ILLEGAL_*}
     * 把本来干净的流程炸掉。
     */
    public SessionInvitationRecord transition(SessionInvitationRecord invitation, String target) {
        if (target.equals(invitation.getStatus())) {
            return invitation;
        }
        require(invitation.getStatus(), target);
        invitation.setStatus(target);
        return invitation;
    }
}