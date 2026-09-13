package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.lifecycle.ApplicationCatalogue;
import com.luxera.companion.application.lifecycle.Availability;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.SessionParticipantRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.SessionRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LAP v2: <b>平台级的应用会话</b> —— 用户无需安装, 打开应用就是开一个会话。
 *
 * <p>"平台级"仍然是刻意的: 会话由平台创建与回收, 应用不管理自己的会话表。加一个新应用不必再想
 * 一遍"会话怎么存、怎么清"。
 *
 * <h2>三条归属不变量在这里兑现</h2>
 * <p>v1 有四条, 全部绕着安装转(会话的应用 = 安装的应用, 会话的 principal = 安装的 principal)。
 * 安装没了, 那四条里只有版本那条原样留下, 另外两条换成了参与者视角的说法 —— 而第三条是新的,
 * 它补上的是一个 v1 结构上不可能出现的问题: <em>人太多</em>。
 *
 * <pre>
 *   1. 会话的 versionId 属于该 applicationId, 且该版本行存在
 *   2. 会话行上记的 owner 真的在参与者表里有一行
 *   3. 会话的 ACTIVE 参与者数不超过它自己的 maxParticipants
 * </pre>
 *
 * <p><b>第 2 条只要求"有一行", 不要求那一行是 ACTIVE。</b>这是一个想清楚之后才放松的判据:
 * 开局的人当然可能中途退出, 而那时候会话该继续存在 —— 把"owner 已退出"判成数据损坏, 会让
 * 一盘别人还在下的棋因为一个人在别处点了"退出"而整体不可用。这一条真正要挡的是<em>另一件事</em>:
 * 会话行上的 owner 字段被改成了一个从来没在这个会话里出现过的 principal。那才是自相矛盾。
 * 至于谁有权做什么, 由参与者行上的 {@code role} 回答, 不由会话行上的 owner 字段回答 ——
 * owner 是<em>出处</em>, 不是<em>权柄</em>。
 *
 * <p>写在 {@link #verifyIntegrity} <em>一个</em>方法里而不是散在调用点: 校验的意义在于每条路径
 * 都问同一组问题。散着写的话, 第四条不变量加进来时总会漏掉一处, 而漏掉的那一处就是脏数据
 * 一路通行的入口。
 *
 * <p><b>刻意不校验"调用方是不是会话的属主"。</b>v1 的注释里已经写过这一条, v2 让它更成立:
 * 一盘棋的会话由先落座的那个人开启, 但对手当然要能在同一个会话的资源上行动 —— 那正是 v2 存在的
 * 理由。调用方<em>有没有资格</em>行动由权限层回答({@code PermissionEvaluator} 会检查它自己那条
 * 参与者行, 没有就 {@code NOT_A_PARTICIPANT})。
 */
@Slf4j
@Service
public class ApplicationSessionService {

    /**
     * {@link #visibleSessions} 的上限。
     *
     * <p>50 是个"比任何一个人的真实使用量大得多、又小到一次查询拿得完"的数。它的存在不是为了
     * 性能, 是为了让<em>没有上限</em>这件事从一开始就不可能: 会话表的行数由用户行为决定, 而一个
     * 进程内端口方法返回一个与用户行为同阶的列表, 迟早会有一次把它读进内存的调用发生在
     * 一个开了几千场会话的应用上。
     */
    static final int VISIBLE_SESSION_LIMIT = 50;

    private final ApplicationSessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final ApplicationVersionRepository versions;
    private final ManifestRegistry manifests;
    private final ParticipantService participantService;
    private final ApplicationCatalogue catalogue;

    public ApplicationSessionService(ApplicationSessionRepository sessions,
                                     SessionParticipantRepository participants,
                                     ApplicationVersionRepository versions,
                                     ManifestRegistry manifests,
                                     ParticipantService participantService,
                                     ApplicationCatalogue catalogue) {
        this.sessions = sessions;
        this.participants = participants;
        this.versions = versions;
        this.manifests = manifests;
        this.participantService = participantService;
        this.catalogue = catalogue;
    }

    // ─────────────────────────── 开启 ───────────────────────────

    /**
     * 打开一个<b>新的</b>会话, 并把调用方记为 OWNER。
     *
     * <p>"每次都新建"是刻意的, 也是它与 {@link #ensureSession} 的全部区别: 用户在应用市场点
     * "打开"就是要开一局新的, 而不是被塞回昨天那局。想续上一局的人该走 {@code ensureSession}
     * 那条路(进程内调用), 或者直接拿着旧会话 id 回来。
     */
    @Transactional
    public ApplicationSessionRecord launch(String applicationId, ResolvedPrincipal principal) {
        return launch(applicationId, principal, null, null, null);
    }

    @Transactional
    public ApplicationSessionRecord launch(String applicationId,
                                           ResolvedPrincipal principal,
                                           String conversationId,
                                           Integer minParticipants,
                                           Integer maxParticipants) {
        // §4.1 的第二列: 这个应用此刻允许开新会话吗。挂在"开"这一条路上而不是挂在两处 ——
        // ensureSession 找不到活会话时也走这里, 于是"被下架的应用不能凭空多出新会话"只有一处判据。
        Availability availability = catalogue.availabilityOf(applicationId);
        if (!availability.allowsNewSession()) {
            throw new SessionException("APPLICATION_NOT_AVAILABLE",
                    "应用 " + applicationId + " 当前不可开新会话(可用性: " + availability
                            + ", 十态原值见 application.status)");
        }
        ApplicationManifest manifest = manifests.published(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "没有已发布的应用 " + applicationId));
        ApplicationVersionRecord version = versions
                .findByApplicationIdAndVersion(applicationId, manifest.version())
                .orElseThrow(() -> new SessionException("VERSION_NOT_PUBLISHED",
                        "应用 " + applicationId + " v" + manifest.version() + " 没有已发布版本行"));

        ApplicationSessionRecord session = new ApplicationSessionRecord();
        session.setApplicationId(applicationId);
        session.setVersionId(version.getId());
        session.setOwnerPrincipalType(principal.typeName());
        session.setOwnerPrincipalId(principal.principalId());
        session.setStatus(ApplicationSessionRecord.STATUS_CREATED);
        session.setVisibility(ApplicationSessionRecord.VISIBILITY_UNLISTED);
        session.setJoinPolicy(ApplicationSessionRecord.JOIN_INVITE_ONLY);
        session.setMinParticipants(minParticipants == null ? 1 : Math.max(1, minParticipants));
        session.setMaxParticipants(maxParticipants == null ? 8 : Math.max(1, maxParticipants));
        session.setConversationId(conversationId);
        session.setCorrelationId(principal.correlationId());
        session.setLastActiveAt(LocalDateTime.now());
        ApplicationSessionRecord saved = sessions.save(session);

        participantService.join(saved.getId(), principal, SessionParticipantRecord.ROLE_OWNER, false);
        ApplicationSessionRecord current = sessions.findById(saved.getId()).orElse(saved);
        verifyIntegrity(current);
        log.info("[ApplicationSession] 开启 {} principal={}:{} (会话 {})",
                applicationId, principal.typeName(), principal.principalId(), current.getId());
        return current;
    }

    /**
     * 把"开启选项"里 {@link #launch} 不管的那两列落到会话行上 —— {@code visibility} 与
     * {@code joinPolicy}。空值<b>不覆盖</b>: 一个没有意见的调用方得到的应当是平台的默认姿态
     * ({@code UNLISTED} / {@code INVITE_ONLY}), 而不是一个字面上的 {@code null}。
     *
     * <p>为什么单开一个方法而不是给 {@code launch} 再加两个参数: 那条通用开启路径的五个参数
     * 已经说出了它要说的全部("哪个应用、谁、哪段对话、几个人"); 可见性与加入策略是
     * <em>入口自己的姿态</em> —— 从 REST 开的人有自己的一套默认, 从对话里开的人有另一套。
     * 把它们塞进 {@code launch}, 就等于让每个入口都要知道另外两个入口的默认值。
     *
     * <p>非法值(不在那一列的定义域里)一律<b>拒绝</b>, 不静默回落: 一个把 {@code PRIVATE}
     * 拼成 {@code PRIVTE} 的调用方, 如果拿到的是一个"看起来成功了"的 UNLISTED 会话, 它会在
     * 某一天发现自己的私密会话被列在了别人屏幕上, 而日志里什么也没有。
     */
    @Transactional
    public ApplicationSessionRecord applyLaunchOptions(String sessionId, String visibility, String joinPolicy) {
        ApplicationSessionRecord session = require(sessionId);
        boolean changed = false;
        if (visibility != null && !visibility.isBlank()) {
            session.setVisibility(requireOneOf("visibility", visibility,
                    ApplicationSessionRecord.VISIBILITY_PUBLIC,
                    ApplicationSessionRecord.VISIBILITY_UNLISTED,
                    ApplicationSessionRecord.VISIBILITY_PRIVATE));
            changed = true;
        }
        if (joinPolicy != null && !joinPolicy.isBlank()) {
            session.setJoinPolicy(requireOneOf("joinPolicy", joinPolicy,
                    ApplicationSessionRecord.JOIN_OPEN,
                    ApplicationSessionRecord.JOIN_INVITE_ONLY,
                    ApplicationSessionRecord.JOIN_CLOSED));
            changed = true;
        }
        return changed ? sessions.save(session) : session;
    }

    private static String requireOneOf(String field, String value, String... allowed) {
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new SessionException("INVALID_ARGUMENT",
                field + " 只能是 " + String.join(" / ", allowed) + ", 收到: " + value);
    }

    /**
     * 版本行的 id → 版本<em>号</em>(如 {@code 1.0.0})。
     *
     * <p>会话行上存的是 {@code version_id}(UUID), 因为那是外键该有的样子; 但对外说
     * "你开的是哪一版"时必须说版本号 —— {@code 3f2a…-…} 对人、对客户端都没有意义(§16)。
     * 这一层转换放在服务里而不是控制器里: 哪个字段是 id、哪个是给人看的号, 是数据模型的知识,
     * 不是 HTTP 的知识。
     *
     * <p>版本行不在时返回 {@code null} 而不是抛: 一个会话的版本行被删掉是<em>数据损坏</em>,
     * 但那不该让"看一眼这个会话"整个失败 —— 三个归属不变量已经在写路径上守住了
     * "版本行必然存在"。这里降级成一个空版本号, 比 500 有用。
     */
    @Transactional(readOnly = true)
    public String versionLabel(String versionId) {
        if (versionId == null) {
            return null;
        }
        return versions.findById(versionId).map(ApplicationVersionRecord::getVersion).orElse(null);
    }

    /**
     * 保证这个 principal 在这个应用里有一个可用的会话 —— <b>幂等</b>。
     *
     * <p>这是 v1 {@code ensureInstalled} 的直接继任者, 也是"打开应用即用"那句话的实现: 第一次
     * 用到时建一个会话, 之后每次都是无操作。会话解析的第 4/5 档合起来就是这一个方法。
     *
     * <p>找的是"这个 principal <em>参与的</em>、属于这个应用、还在 ACTIVE"的会话里最近活跃的
     * 那一个。刻意带上 ACTIVE: 一个 WAITING 或 PAUSED 的会话不该被拿来当新动作的落脚点 ——
     * 那会写出一个必然被 {@code SESSION_NOT_ACTIVE} 拒掉的动作。
     */
    @Transactional
    public ApplicationSessionRecord ensureSession(String applicationId, ResolvedPrincipal principal) {
        return findLive(applicationId, principal).orElseGet(() -> launch(applicationId, principal));
    }

    /**
     * 这个 principal 在这个应用里最近活跃的那个 ACTIVE 会话 —— <b>只查, 不建</b>。
     *
     * <p>"查"与"建"分开是有意的。{@code ensureSession} 会把它们接起来, 但
     * {@code ActionGateway} 的会话解析第 4 档要的只是"查": 一次<em>读</em>动作
     * ({@code pendingActions}) 不该因为"这个人还没开过会话"就顺手在库里落一行。查询无副作用,
     * 于是它能在只读路径上安全地调用。
     */
    public Optional<ApplicationSessionRecord> findLive(String applicationId, ResolvedPrincipal principal) {
        List<SessionParticipantRecord> memberships = participantService
                .activeMembershipsOf(principal.type(), principal.principalId());
        return memberships.stream()
                .map(m -> sessions.findById(m.getSessionId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(s -> applicationId.equals(s.getApplicationId()))
                .filter(ApplicationSessionRecord::active)
                .max(Comparator.comparing(ApplicationSessionRecord::getLastActiveAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    // ─────────────────────────── 使用期校验 ───────────────────────────

    /**
     * 找到并校验一个会话: 存在、未结束、归属自洽、且属于这个应用。
     *
     * <p><b>只把 {@code ENDED} 当作不可用。</b> WAITING / PAUSED 的会话必须能通过这一关, 否则
     * "等人时看一眼棋盘"会在会话解析那一步就失败, 而不是在状态那一步给出一个说得清的
     * {@code SESSION_NOT_ACTIVE}。级别与状态的匹配由
     * {@code ApplicationSessionStateMachine.requireAllows} 单独负责。
     *
     * @throws SessionException 任何一条不满足
     */
    public ApplicationSessionRecord requireUsable(String sessionId, String applicationId) {
        ApplicationSessionRecord session = require(sessionId);
        if (session.ended()) {
            throw new SessionException("SESSION_ENDED", "会话已结束: " + sessionId);
        }
        verifyIntegrity(session);
        if (applicationId != null && !applicationId.equals(session.getApplicationId())) {
            throw new SessionException("SESSION_APPLICATION_MISMATCH",
                    "会话 " + sessionId + " 属于应用 " + session.getApplicationId()
                            + ", 不是 " + applicationId);
        }
        return session;
    }

    public ApplicationSessionRecord require(String sessionId) {
        return sessions.findById(sessionId).orElseThrow(() ->
                new SessionException("UNKNOWN_SESSION", "会话不存在: " + sessionId));
    }

    /**
     * {@link #requireUsable} 的"答不出来就是空"版本 —— 给<em>平台自己的记账</em>用。
     *
     * <p>存在的理由是"谁说的"决定了"答不上来该怎么办"。调用方在请求里给的会话 id 是一条
     * <b>断言</b>: 它错了就该知道({@code SESSION_ENDED} / {@code UNKNOWN_SESSION})。而资源行上
     * 那个 {@code session_id} 是<b>平台的记账</b> —— 用户从没说过"我要在 7 天前那个会话里提醒我",
     * 他只知道自己在用提醒应用。于是当记账过时了(会话被回收器收掉), 正确的反应是重新解一个,
     * 而不是把一条陈旧记录当成错误抛到用户脸上、让这个应用<em>永久</em>不可用。
     */
    public Optional<ApplicationSessionRecord> findUsable(String sessionId, String applicationId) {
        try {
            return Optional.of(requireUsable(sessionId, applicationId));
        } catch (SessionException e) {
            return Optional.empty();
        }
    }

    /**
     * 三条归属不变量的唯一实现。创建时与每次使用时都跑 —— 一次写歪的数据不该因为"当时没查"
     * 而在之后一路通行。
     */
    public void verifyIntegrity(ApplicationSessionRecord session) {
        ApplicationVersionRecord version = versions.findById(session.getVersionId())
                .orElseThrow(() -> new SessionException("SESSION_VERSION_MISMATCH",
                        "会话 " + session.getId() + " 指向的版本不存在"));
        if (!version.getApplicationId().equals(session.getApplicationId())) {
            throw new SessionException("SESSION_VERSION_MISMATCH",
                    "版本 " + version.getId() + " 属于 " + version.getApplicationId()
                            + ", 不是 " + session.getApplicationId());
        }

        boolean ownerKnown = participants
                .findBySessionIdAndPrincipalTypeAndPrincipalId(session.getId(),
                        session.getOwnerPrincipalType(), session.getOwnerPrincipalId())
                .isPresent();
        if (!ownerKnown) {
            throw new SessionException("SESSION_OWNER_MISMATCH",
                    "会话 " + session.getId() + " 记的开局人 "
                            + session.getOwnerPrincipalType() + ":" + session.getOwnerPrincipalId()
                            + " 从来不是它的参与者");
        }

        long active = participants.countBySessionIdAndStatus(session.getId(),
                SessionParticipantRecord.STATUS_ACTIVE);
        if (active > session.getMaxParticipants()) {
            throw new SessionException("SESSION_CAPACITY_EXCEEDED",
                    "会话 " + session.getId() + " 有 " + active + " 个参与者, 超过上限 "
                            + session.getMaxParticipants());
        }
    }

    // ─────────────────────────── 修改 ───────────────────────────

    @Transactional
    public void touch(String sessionId) {
        sessions.findById(sessionId).ifPresent(row -> row.setLastActiveAt(LocalDateTime.now()));
    }

    @Transactional
    public void end(String sessionId) {
        sessions.findById(sessionId).ifPresent(row -> {
            if (!row.ended()) {
                row.setStatus(ApplicationSessionRecord.STATUS_ENDED);
                row.setEndedAt(LocalDateTime.now());
                sessions.save(row);
            }
        });
    }

    /**
     * 结束空闲太久的会话, 返回条数。由 {@link SessionReaperJob} 定期调用。
     *
     * <p>扫 {@code ACTIVE} 与 {@code WAITING} 两种: 一个等了七天还没等到人的会话同样该收掉。
     * 已经结束的不动 —— 反复 save 只会让 JPA 产生无谓的 UPDATE。
     */
    @Transactional
    public int reapIdle(long idleHours) {
        if (idleHours <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(idleHours);
        int reaped = 0;
        for (String status : List.of(ApplicationSessionRecord.STATUS_ACTIVE,
                ApplicationSessionRecord.STATUS_WAITING)) {
            for (ApplicationSessionRecord row : sessions.findByStatusAndLastActiveAtBefore(status, cutoff)) {
                row.setStatus(ApplicationSessionRecord.STATUS_ENDED);
                row.setEndedAt(LocalDateTime.now());
                sessions.save(row);
                reaped++;
            }
        }
        return reaped;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public List<ApplicationSessionRecord> ofApplication(String applicationId) {
        return sessions.findByApplicationId(applicationId);
    }

    /**
     * §85 —— 这段对话里开着的全部会话, 最近活跃的在前。
     *
     * <p><b>不筛状态, 也不筛参与者。</b> 前者: "这段对话里开过什么"是一个<em>历史</em>问题 ——
     * 一局昨天结束的棋仍然是这段对话里发生过的事, 把它藏起来会让聊天界面在第二天
     * 莫名其妙地少一条记录。后者: 说话的是聊天平台, 它在进程内代表<em>一段对话</em>,
     * 而不是某一个参与者; 谁看得到这段对话, 是聊天平台自己的事(它已经验过了)。
     * 在这里再筛一次参与者, 只会让"我在这段对话里看不见我朋友开的那一局"这种
     * 没人定义过的行为长出来。
     */
    public List<ApplicationSessionRecord> ofConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return sessions.findByConversationId(conversationId).stream()
                .sorted(Comparator.comparing(ApplicationSessionRecord::getLastActiveAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /**
     * §59 —— <b>这个 principal 在这个应用里看得见的会话</b>, 最近活跃的在前。
     *
     * <p>两部分的并集: 他<em>此刻在场</em>的, 加上任何人<em>都进得去</em>的({@code OPEN} 且还活着)。
     * 判据、以及"退场过的不在里面"的理由, 都写在 {@code ApplicationRuntimePort.sessionsOf} 上 ——
     * 这个方法是那句话的实现, 不另立一套。
     *
     * <p><b>参与的在前面, 不是因为好看。</b> 调用方拿这份列表做的第一件事是"我该在哪一场里动手",
     * 而截断(见 {@link #VISIBLE_SESSION_LIMIT})是按位置截的。如果把 {@code OPEN} 的陌生人会话
     * 排前面, 一个开了六百场公开棋局的平台就会把某人自己的那一场挤出榜单 —— 而那正是他唯一真正
     * 需要的一行。于是排序键是 ({@code joined} 降序, {@code lastActiveAt} 降序), 截断于是永远
     * 先切别人。
     *
     * <p>为什么要 {@code principal} 可空: 平台内部有些查询不站在任何人身上(例如运维视图)。
     * 那时 {@code joined} 恒为 false, 结果退化成"这个应用里所有公开开着的会话"—— 那不是错误,
     * 是这个问题在没有"谁"的时候唯一说得通的答案。
     */
    public List<SessionRef> visibleSessions(String applicationId, ResolvedPrincipal principal) {
        if (applicationId == null || applicationId.isBlank()) {
            return List.of();
        }
        Set<String> mine = principal == null ? Set.of()
                : participantService.activeMembershipsOf(principal.type(), principal.principalId())
                        .stream()
                        .map(SessionParticipantRecord::getSessionId)
                        .collect(Collectors.toSet());

        List<ApplicationSessionRecord> visible = sessions.findByApplicationId(applicationId).stream()
                .filter(session -> !session.ended())
                .filter(session -> mine.contains(session.getId())
                        || ApplicationSessionRecord.JOIN_OPEN.equals(session.getJoinPolicy()))
                .sorted(orderByMembershipThenRecency(mine))
                .limit(VISIBLE_SESSION_LIMIT)
                .toList();

        List<SessionRef> out = new ArrayList<>(visible.size());
        for (ApplicationSessionRecord session : visible) {
            out.add(ref(session, mine.contains(session.getId()), participants.countBySessionIdAndStatus(
                    session.getId(), SessionParticipantRecord.STATUS_ACTIVE)));
        }
        return out;
    }

    /**
     * ({@code 我参与的} 在前, {@code lastActiveAt} 倒序)。{@code lastActiveAt} 为空的行排在最后 ——
     * 它可能是任何时间, 猜一个出来不如承认不知道。
     */
    private static Comparator<ApplicationSessionRecord> orderByMembershipThenRecency(Set<String> mine) {
        return Comparator
                .comparingInt((ApplicationSessionRecord s) -> mine.contains(s.getId()) ? 0 : 1)
                .thenComparing(ApplicationSessionRecord::getLastActiveAt,
                        Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()));
    }

    private static SessionRef ref(ApplicationSessionRecord session, boolean joined, long activeCount) {
        return new SessionRef(session.getId(), session.getApplicationId(), session.getStatus(),
                session.getVisibility(), session.getJoinPolicy(), (int) activeCount,
                session.getMaxParticipants(), joined, session.getOwnerPrincipalType(),
                session.getOwnerPrincipalId(), session.getConversationId());
    }

    /** 这个 principal 参与的全部活跃会话 —— 真人 UI 的"我正在用的应用"用的就是它。 */
    public List<ApplicationSessionRecord> ofPrincipal(PrincipalType type, String principalId) {
        return participantService.activeMembershipsOf(type, principalId).stream()
                .map(m -> sessions.findById(m.getSessionId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ApplicationSessionRecord::getLastActiveAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }
}
