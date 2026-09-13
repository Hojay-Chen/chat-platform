package com.luxera.companion.application.permission;

import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.domain.SessionPermissionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.application.repository.SessionPermissionRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LAP v2: <b>权限 = Participant × Session permission × Capability × Action × Risk。</b>
 *
 * <p>五个维度没变, 变的是第一个。v1 的第一维是 {@code Installation} —— "你装了这个应用吗";
 * v2 是 {@code Participant} —— "<b>你在这一局里吗</b>"。这一换, 作用域从"这个应用"收窄到了
 * "这一个会话", 而整个 LAP v2 要的"多个 principal 共用一个应用实例"正是靠这次收窄才成立的:
 * 两个人各自在这场里有自己的授权, 谁也不能拿自己在别处的身份到这里来用。
 *
 * <pre>
 *   1. 身份存在?                                   否 → PRINCIPAL_REQUIRED
 *   2. 动作存在?                                   否 → ACTION_NOT_FOUND
 *   3. 会话存在?                                   否 → SESSION_REQUIRED
 *   4. 是这场里的参与者吗?                          否 → NOT_A_PARTICIPANT
 *      参与者还是 ACTIVE 吗?                        否 → PARTICIPANT_INACTIVE
 *   5. 是读动作?                                   是 → 放行
 *   6. 有覆盖这个动作的授权?                        否 → NOT_AUTHORIZED
 *      没过期?                                     否 → GRANT_EXPIRED
 *      级别够?                                     否 → NOT_AUTHORIZED
 *      风险没超上限?                               否 → RISK_TOO_HIGH
 *   7. 动作风险本身允许无人值守执行?                中 → REQUIRE_CONFIRMATION; 高 → RISK_TOO_HIGH
 * </pre>
 *
 * <p><b>第 4 步取代 v1 的"没装就拒绝"。</b>那个错误码在整个代码库里<b>必须彻底消失</b> ——
 * 它描述的状态(安装)已经不是一个概念了。留下一个永远发不出来的码, 只会让下一个读到它的人以为
 * 安装还在。"不在场"与"在场但已离开"仍然是两种拒绝, 因为它们要调用方做的事不同:
 * 前者该去找人邀请自己, 后者该重新加入。
 *
 * <p><b>第 7 步是刻意的保守</b>: {@code MEDIUM} 要求显式确认, {@code HIGH}/{@code CRITICAL}
 * 直接拒绝。不是因为做不到, 而是因为在一个真人+Agent 共处的系统里, "Agent 自主执行了一个高
 * 风险动作"目前还没有合适的兜底机制去承接。等有了, 再放开。
 *
 * <p>这个类<em>不</em>碰 {@code contracts} 的 {@code PermissionLevel}/{@code RiskLevel} 定义,
 * 只是按枚举声明顺序比较 —— 顺序即严重度, 改顺序就是改语义, 所以枚举里没有任何 {@code UNKNOWN}
 * 之类的填充值。
 */
@Slf4j
@Service
public class PermissionEvaluator {

    private final SessionParticipantRepository participants;
    private final SessionPermissionRepository permissions;

    public PermissionEvaluator(SessionParticipantRepository participants,
                               SessionPermissionRepository permissions) {
        this.participants = participants;
        this.permissions = permissions;
    }

    public PermissionDecision evaluate(ApplicationManifest manifest,
                                       ApplicationManifest.ActionDecl spec,
                                       String sessionId,
                                       PrincipalType principalType,
                                       String principalId) {
        if (principalType == null || principalId == null || principalId.isBlank()) {
            return PermissionDecision.deny("PRINCIPAL_REQUIRED", "调用方身份缺失");
        }
        if (spec == null) {
            return PermissionDecision.deny("ACTION_NOT_FOUND", "动作不存在");
        }
        if (sessionId == null || sessionId.isBlank()) {
            // 网关的第 3 步保证会话一定解析得出来, 所以走到这里意味着调用方绕过了它。
            return PermissionDecision.deny("SESSION_REQUIRED", "动作缺少会话上下文");
        }

        SessionParticipantRecord participant = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(
                        sessionId, principalType.name(), principalId)
                .orElse(null);
        if (participant == null) {
            return PermissionDecision.deny("NOT_A_PARTICIPANT",
                    principalType + " " + principalId + " 不在会话 " + sessionId + " 里");
        }
        if (!participant.active()) {
            return PermissionDecision.deny("PARTICIPANT_INACTIVE",
                    "参与者状态已 " + participant.getStatus());
        }

        if (spec.isRead()) {
            // 读动作只要在场就能读: 它的风险由 manifest 声明为 NONE 是被校验过的,
            // 而"读一眼自己所在会话的状态"再要一次授权只会让 UI 变得莫名其妙。
            return PermissionDecision.allow();
        }

        List<SessionPermissionRecord> covering = permissions.findByParticipantId(participant.getId()).stream()
                .filter(g -> g.covers(spec.id(), spec.capability()))
                .toList();
        if (covering.isEmpty()) {
            return PermissionDecision.deny("NOT_AUTHORIZED",
                    "参与者没有获得动作 " + spec.id() + " 的授权");
        }

        List<SessionPermissionRecord> live = covering.stream().filter(g -> !g.expired()).toList();
        if (live.isEmpty()) {
            return PermissionDecision.deny("GRANT_EXPIRED",
                    "动作 " + spec.id() + " 的授权已过期");
        }

        boolean levelOk = live.stream()
                .anyMatch(g -> levelRank(g.getPermissionLevel()) >= levelRank(spec.permission().name()));
        if (!levelOk) {
            return PermissionDecision.deny("NOT_AUTHORIZED",
                    "授权级别低于动作 " + spec.id() + " 要求的 " + spec.permission());
        }

        boolean ceilingOk = live.stream()
                .anyMatch(g -> riskRank(g.getRiskCeiling()) >= riskRank(spec.risk().name()));
        if (!ceilingOk) {
            return PermissionDecision.deny("RISK_TOO_HIGH",
                    "动作风险 " + spec.risk() + " 超过授权上限");
        }

        return riskBand(spec.risk());
    }

    private PermissionDecision riskBand(RiskLevel risk) {
        if (risk == null) {
            return PermissionDecision.allow();
        }
        return switch (risk) {
            case NONE, LOW -> PermissionDecision.allow();
            case MEDIUM -> PermissionDecision.confirm("CONFIRM_REQUIRED",
                    "中等风险动作需要显式确认");
            case HIGH, CRITICAL -> PermissionDecision.deny("RISK_TOO_HIGH",
                    "高风险动作 (" + risk + ") 不允许自主执行");
        };
    }

    /** READ(0) < WRITE(1) < EXECUTE(2) —— 枚举声明顺序即权限强弱。 */
    private static int levelRank(String permissionLevel) {
        return switch (permissionLevel) {
            case "READ" -> 0;
            case "WRITE" -> 1;
            case "EXECUTE" -> 2;
            default -> -1;
        };
    }

    /** NONE(0) < LOW(1) < MEDIUM(2) < HIGH(3) < CRITICAL(4)。 */
    private static int riskRank(String riskLevel) {
        return switch (riskLevel) {
            case "NONE" -> 0;
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> -1;
        };
    }
}
