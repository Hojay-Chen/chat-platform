package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.domain.SessionPermissionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.application.repository.SessionPermissionRepository;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LAP v2: <b>谁在这个会话里, 以及他能做什么</b> —— v1 的 {@code InstallationService} 的位置。
 *
 * <p>替换的不是一个方法, 而是一个问题。v1 问"这个 principal 装了这个应用吗", 答案一存就是
 * 永久; v2 问"这个 principal 在这一个会话里吗", 答案随会话生灭。于是本类里<em>没有</em>任何
 * 跨会话的查询, 也没有任何"给某人授一次权, 处处生效"的入口 —— 那种入口正是这次重构要拆掉的东西。
 *
 * <h2>角色决定默认 profile, profile 展开成授权行</h2>
 * <p>方案里 {@code session_participant.role} 与 {@code session_permission} 是两个东西, 但它们
 * 的关系没人定义过。照抄会出现两套并行的权限真相。这里定死一条:
 *
 * <pre>
 *   OWNER / MEMBER → 该应用声明的<em>全部</em>能力, 级别与风险上限取该能力下动作的最高值
 *   OBSERVER       → 同样的能力集合, 但级别固定 READ (能看不能改)
 * </pre>
 *
 * <p>展开是"<b>只补不覆盖</b>"的({@link #grantMissing}): 加入两次不会把调用方手工调过的
 * 级别改回去 —— 与 v1 的 {@code grantMissing} 一字不差的语义, 因为那条语义是对的。
 *
 * <h2>为什么这里没有"真人分支"</h2>
 * <p>{@code principal.type()} 在本类里只被读取一次, 用来填 {@code companion_id}/{@code user_id}
 * 两个描述性字段。权限、容量、策略一律不看它 —— 真人、Agent、外部 Agent 走的是同一段代码。
 */
@Slf4j
@Service
public class ParticipantService {

    private final SessionParticipantRepository participants;
    private final SessionPermissionRepository permissions;
    private final ApplicationSessionRepository sessions;
    private final ApplicationSessionStateMachine stateMachine;
    private final ManifestRegistry manifests;

    public ParticipantService(SessionParticipantRepository participants,
                              SessionPermissionRepository permissions,
                              ApplicationSessionRepository sessions,
                              ApplicationSessionStateMachine stateMachine,
                              ManifestRegistry manifests) {
        this.participants = participants;
        this.permissions = permissions;
        this.sessions = sessions;
        this.stateMachine = stateMachine;
        this.manifests = manifests;
    }

    // ─────────────────────────── 加入 ───────────────────────────

    /**
     * 把调用方自己加进会话。幂等: 已经在里面就原样返回, 只补齐缺的授权。
     *
     * @param viaInvitation 通过邀请链接进入 —— 唯一能绕过 {@code INVITE_ONLY} 的路径
     */
    @Transactional
    public SessionParticipantRecord join(String sessionId, ResolvedPrincipal principal, String role,
                                         boolean viaInvitation) {
        return join(sessionId, principal.typeName(), principal.principalId(),
                principal.companionId(), principal.userId(), role, viaInvitation);
    }

    @Transactional
    public SessionParticipantRecord join(String sessionId,
                                         String principalType,
                                         String principalId,
                                         String companionId,
                                         String userId,
                                         String role,
                                         boolean viaInvitation) {
        ApplicationSessionRecord session = requireSession(sessionId);
        if (session.ended()) {
            throw new SessionException("SESSION_ENDED", "会话已结束: " + sessionId);
        }

        SessionParticipantRecord existing = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(sessionId, principalType, principalId)
                .orElse(null);

        if (existing == null) {
            requireAdmission(session, principalType, principalId, viaInvitation);
            long active = participants.countBySessionIdAndStatus(sessionId,
                    SessionParticipantRecord.STATUS_ACTIVE);
            if (active >= session.getMaxParticipants()) {
                throw new SessionException("SESSION_FULL",
                        "会话 " + sessionId + " 已满 (" + session.getMaxParticipants() + " 人)");
            }
            existing = new SessionParticipantRecord();
            existing.setSessionId(sessionId);
            existing.setPrincipalType(principalType);
            existing.setPrincipalId(principalId);
        } else if (!existing.active()) {
            existing.setLeftAt(null);
        } else {
            // 已在场者: 角色/配置已是既定事实(尤其 OWNER 不该被邀请的 MEMBER 覆盖),
            // 幂等只补齐授权与状态, 不改所有权归属。
            grantsFor(session.getApplicationId(), existing);
            syncStatus(session);
            log.info("[Participant] {}:{} 已在会话 {} 中, 跳过角色重置",
                    principalType, principalId, sessionId);
            return existing;
        }

        existing.setRole(normalizeRole(role, session, principalType, principalId));
        existing.setStatus(SessionParticipantRecord.STATUS_ACTIVE);
        if (existing.getPermissionProfile() == null || existing.getPermissionProfile().isBlank()) {
            existing.setPermissionProfile(existing.getRole());
        }
        if (companionId != null) existing.setCompanionId(companionId);
        if (userId != null) existing.setUserId(userId);
        participants.save(existing);

        grantsFor(session.getApplicationId(), existing);
        syncStatus(session);
        log.info("[Participant] {}:{} 加入会话 {} 作为 {}",
                principalType, principalId, sessionId, existing.getRole());
        return existing;
    }

    /**
     * 换人进来之前先问三件事, <b>顺序不能换</b>: 是不是主、会话还收不收人、是不是被邀请的。
     *
     * <p>{@code CLOSED} 必须在"被邀请"<em>之前</em>判。反过来写的话, 一张还没被用掉的邀请链接
     * 就能把一个已经关闸的会话重新打开, 于是 {@code CLOSED} 与 {@code INVITE_ONLY} 变成两个
     * 名字不同、行为逐字相同的枚举值 —— 而它们的差别恰恰是调用方唯一需要知道的:
     *
     * <pre>
     *   OPEN         谁都能进
     *   INVITE_ONLY  被邀请的能进
     *   CLOSED       谁也别进(下到一半的棋不该被塞进第三个人)
     * </pre>
     *
     * <p>主人不受任何策略约束 —— 它本来就在里面。这条豁免实际只在 {@code launch} 的那一刻
     * 起作用(那时会话刚建出来、策略还没生效), 但留着它让"开一个会话"不必依赖任何策略取值。
     *
     * @param viaInvitation 通过邀请链接进入 —— 唯一能绕过 {@code INVITE_ONLY} 的路径,
     *                      但它绕不过 {@code CLOSED}
     */
    private void requireAdmission(ApplicationSessionRecord session,
                                  String principalType, String principalId, boolean viaInvitation) {
        boolean owner = principalType.equals(session.getOwnerPrincipalType())
                && principalId.equals(session.getOwnerPrincipalId());
        if (owner) {
            return;
        }
        if (ApplicationSessionRecord.JOIN_CLOSED.equals(session.getJoinPolicy())) {
            throw new SessionException("SESSION_JOIN_CLOSED", "会话 " + session.getId() + " 不接受加入");
        }
        if (viaInvitation) {
            return;
        }
        if (ApplicationSessionRecord.JOIN_INVITE_ONLY.equals(session.getJoinPolicy())) {
            throw new SessionException("SESSION_INVITE_ONLY",
                    "会话 " + session.getId() + " 只接受邀请加入");
        }
    }

    /**
     * 只有开局的人能给 {@code OWNER}。别人自报 OWNER 一律降级成 MEMBER —— 这挡的不是权限
     * (权限来自 profile, 而 profile 是可以被主人改的), 而是"谁说得上话"这件事的所有权。
     */
    private static String normalizeRole(String role, ApplicationSessionRecord session,
                                        String principalType, String principalId) {
        boolean owner = principalType.equals(session.getOwnerPrincipalType())
                && principalId.equals(session.getOwnerPrincipalId());
        if (role == null || role.isBlank()) {
            return owner ? SessionParticipantRecord.ROLE_OWNER : SessionParticipantRecord.ROLE_MEMBER;
        }
        if (SessionParticipantRecord.ROLE_OWNER.equals(role) && !owner) {
            return SessionParticipantRecord.ROLE_MEMBER;
        }
        if (!SessionParticipantRecord.ROLE_MEMBER.equals(role)
                && !SessionParticipantRecord.ROLE_OBSERVER.equals(role)) {
            return owner ? SessionParticipantRecord.ROLE_OWNER : SessionParticipantRecord.ROLE_MEMBER;
        }
        return role;
    }

    // ─────────────────────────── 离开 / 移除 ───────────────────────────

    @Transactional
    public void leave(String sessionId, ResolvedPrincipal principal) {
        SessionParticipantRecord row = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(
                        sessionId, principal.typeName(), principal.principalId())
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT",
                        principal.typeName() + " " + principal.principalId()
                                + " 不在会话 " + sessionId + " 里"));
        markGone(row, SessionParticipantRecord.STATUS_LEFT);
    }

    /** 主人移除别人。被移除的人留在表里({@code REMOVED}), 于是"他曾经在场"查得到。 */
    @Transactional
    public void remove(String sessionId, ResolvedPrincipal actor, String principalType, String principalId) {
        SessionParticipantRecord me = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(
                        sessionId, actor.typeName(), actor.principalId())
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT", "调用方不在这个会话里"));
        if (!me.owner()) {
            throw new SessionException("NOT_SESSION_OWNER", "只有会话的主人能移除参与者");
        }
        SessionParticipantRecord target = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(sessionId, principalType, principalId)
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT",
                        principalType + " " + principalId + " 不在会话 " + sessionId + " 里"));
        markGone(target, SessionParticipantRecord.STATUS_REMOVED);
    }

    private void markGone(SessionParticipantRecord row, String status) {
        row.setStatus(status);
        row.setLeftAt(LocalDateTime.now());
        participants.save(row);
        // 授权随人一起走: 留着它只会让下一个人读到"已经不在场的人还能写"。
        permissions.deleteByParticipantId(row.getId());
        syncStatus(requireSession(row.getSessionId()));
    }

    // ─────────────────────────── 状态推进 ───────────────────────────

    /**
     * 按当前在场人数推进会话状态。一条向下的规则, 三个状态都以 {@code minParticipants} 为准:
     *
     * <pre>
     *   CREATED ──(有人了, 但还不够)──→ WAITING ──(active ≥ min)──→ ACTIVE
     * </pre>
     *
     * <p><b>{@code CREATED → WAITING} 那一步不是装饰。</b>没有它 {@code WAITING} 会是一个
     * <em>没有任何代码路径能进入</em>的状态: {@code launch} 建行时置 {@code CREATED}, 紧接着
     * 主人的那一次 join 在不满足 {@code minParticipants} 时只会原样留在 {@code CREATED}。
     * 于是状态机里留着它的转移表、留着 {@code allows} 的分支, 而库里永远不会出现这个值 ——
     * 读到这里的人会以为"等待"是被谁实现的, 然后照着那个不存在的实现改代码。一条只活在
     * 枚举里的状态比没有这个状态更坏。这一段在 v2 之前<em>确实</em>是那样, 是被
     * {@code ParticipantTest.aSessionWaitingForTheSecondPlayerAdvancesWhenTheyArrive}
     * 逼出来的。
     *
     * <p>三个状态各自的含义:
     * <ul>
     *   <li>{@code CREATED} —— 刚开出来, 一个人都还没落座。它只在 {@code launch} 内部存在
     *       一瞬, 因为那一刻紧接着就有一次 owner 的 join。</li>
     *   <li>{@code WAITING} —— 有人在等, 但还不够开局。</li>
     *   <li>{@code ACTIVE} —— 开局了。</li>
     * </ul>
     * 默认 {@code minParticipants=1}, 所以默认配置下 {@code WAITING} 观察不到 —— 主人一进来
     * 人数就够了。它不是"没实现", 是默认值让它没有出现的理由; 把 {@code minParticipants}
     * 调到 2 就会看到它。
     *
     * <p><b>刻意没有反向的那一条, 也没有"人走光了就结束"那一条。</b>两条都是想清楚之后不写的:
     *
     * <ul>
     *   <li><em>ACTIVE 不掉回 WAITING</em> —— 一个已经开始的会话不会因为有人离开而变成"还没开始"。
     *       一局下到一半走了个人, 局面还在那里, 那不是等待, 那是残局。让 {@code minParticipants}
     *       只在开局前起作用, 是这两个状态唯一说得通的分别。</li>
     *   <li><em>人走光了也不结束</em> —— 退场只把那一个人置成 {@code LEFT} 并收回他的授权,
     *       会话本身不动; 再调动作得到的是 {@code PARTICIPANT_INACTIVE}, 而不是
     *       {@code SESSION_ENDED}。这两种拒绝要调用方做的事不同: 前者是"重新加入这个会话",
     *       后者是"这个会话没了, 换一个"。真想收掉一个没人用的会话, 有显式的
     *       {@code DELETE /sessions/{id}} 和回收器 —— 不该由一个成员退出来替所有人做这个决定。</li>
     * </ul>
     */
    @Transactional
    public ApplicationSessionRecord syncStatus(ApplicationSessionRecord session) {
        if (session.ended()) {
            return session;
        }
        long active = participants.countBySessionIdAndStatus(session.getId(),
                SessionParticipantRecord.STATUS_ACTIVE);
        boolean waiting = ApplicationSessionRecord.STATUS_CREATED.equals(session.getStatus())
                || ApplicationSessionRecord.STATUS_WAITING.equals(session.getStatus());
        if (!waiting) {
            return session;
        }
        if (active >= session.getMinParticipants()) {
            return transition(session, ApplicationSessionRecord.STATUS_ACTIVE);
        }
        if (active > 0) {
            // 有人在等, 但还不够开局 —— 这一步让 WAITING 成为一个真的会被写进库的状态。
            return transition(session, ApplicationSessionRecord.STATUS_WAITING);
        }
        return session;
    }

    @Transactional
    public ApplicationSessionRecord transition(ApplicationSessionRecord session, String target) {
        if (target.equals(session.getStatus())) {
            return session;
        }
        stateMachine.require(session.getStatus(), target);
        session.setStatus(target);
        if (ApplicationSessionRecord.STATUS_ACTIVE.equals(target) && session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
        }
        if (ApplicationSessionRecord.STATUS_ENDED.equals(target)) {
            session.setEndedAt(LocalDateTime.now());
        }
        sessions.save(session);
        return session;
    }

    // ─────────────────────────── 授权展开 ───────────────────────────

    /**
     * 按 profile 把缺的授权行补上。<b>只补不覆盖</b> —— 重复加入不该把手工调过的级别改回去。
     */
    @Transactional
    public void grantMissing(SessionParticipantRecord participant, ApplicationManifest manifest) {
        List<SessionPermissionRecord> existing = permissions.findByParticipantId(participant.getId());
        boolean observer = SessionParticipantRecord.ROLE_OBSERVER.equals(participant.effectiveProfile());

        for (ApplicationManifest.CapabilityDecl capability : manifest.capabilities()) {
            boolean present = existing.stream()
                    .anyMatch(g -> capability.id().equals(g.getCapabilityId()) && g.getActionId() == null);
            if (present) {
                continue;
            }
            SessionPermissionRecord grant = new SessionPermissionRecord();
            grant.setParticipantId(participant.getId());
            grant.setCapabilityId(capability.id());
            grant.setPermissionLevel(observer
                    ? PermissionLevel.READ.name()
                    : highestLevel(manifest, capability.id()).name());
            grant.setRiskCeiling(highestRisk(manifest, capability.id()).name());
            permissions.save(grant);
        }
    }

    private void grantsFor(String applicationId, SessionParticipantRecord participant) {
        manifests.published(applicationId).ifPresent(manifest -> grantMissing(participant, manifest));
    }

    /** 该能力下动作要求的最高权限级别 —— 授权不能低于它任何一项。 */
    static PermissionLevel highestLevel(ApplicationManifest manifest, String capabilityId) {
        PermissionLevel highest = PermissionLevel.READ;
        for (ApplicationManifest.ActionDecl action : actionsOf(manifest, capabilityId)) {
            if (action.permission() != null && action.permission().ordinal() > highest.ordinal()) {
                highest = action.permission();
            }
        }
        return highest;
    }

    static RiskLevel highestRisk(ApplicationManifest manifest, String capabilityId) {
        RiskLevel highest = RiskLevel.NONE;
        for (ApplicationManifest.ActionDecl action : actionsOf(manifest, capabilityId)) {
            if (action.risk() != null && action.risk().ordinal() > highest.ordinal()) {
                highest = action.risk();
            }
        }
        return highest;
    }

    private static List<ApplicationManifest.ActionDecl> actionsOf(ApplicationManifest manifest,
                                                                  String capabilityId) {
        List<ApplicationManifest.ActionDecl> out = new ArrayList<>();
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            if (capabilityId.equals(action.capability())) {
                out.add(action);
            }
        }
        return out;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public Optional<SessionParticipantRecord> find(String sessionId, PrincipalType type, String principalId) {
        if (type == null || principalId == null) {
            return Optional.empty();
        }
        return participants.findBySessionIdAndPrincipalTypeAndPrincipalId(
                sessionId, type.name(), principalId);
    }

    public List<SessionParticipantRecord> participantsOf(String sessionId) {
        return participants.findBySessionId(sessionId);
    }

    public List<SessionParticipantRecord> activeParticipantsOf(String sessionId) {
        return participants.findBySessionIdAndStatus(sessionId, SessionParticipantRecord.STATUS_ACTIVE);
    }

    public List<SessionParticipantRecord> activeMembershipsOf(PrincipalType type, String principalId) {
        return participants.findByPrincipalTypeAndPrincipalIdAndStatus(
                type.name(), principalId, SessionParticipantRecord.STATUS_ACTIVE);
    }

    /** 参会者在这局里被授予的能力 —— 端点回显用, 与权限判定读的是同一批行。 */
    public List<String> capabilitiesOf(SessionParticipantRecord participant) {
        return permissions.capabilityIdsOf(participant.getId());
    }

    private ApplicationSessionRecord requireSession(String sessionId) {
        return sessions.findById(sessionId).orElseThrow(() ->
                new SessionException("UNKNOWN_SESSION", "会话不存在: " + sessionId));
    }
}
